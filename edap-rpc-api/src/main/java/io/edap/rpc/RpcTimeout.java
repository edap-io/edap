/*
 * Copyright 2020 The edap Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package io.edap.rpc;

/**
 * 超时的编解码 —— gRPC 与 eRPC 共用。
 *
 * <p><b>★ 核心不变式</b>：<b>线上只传「相对时长」，本地只存「绝对 nanoTime 时刻」</b>。
 * {@link System#nanoTime()} 跨进程不可比，跨机传绝对时刻没有意义；而
 * {@link System#currentTimeMillis()} 会被 NTP 调整跳变，不能当 deadline 的基准。</p>
 *
 * <p>因此两端的分工是：</p>
 * <ul>
 *   <li>线上：把「还剩多久」编码成一个值（gRPC 用 header，eRPC 用帧头 flags）；</li>
 *   <li>落地：立刻换算成本进程的绝对 {@code nanoTime} 时刻，
 *       由 {@link RpcContext#deadlineNanos()} 持有。</li>
 * </ul>
 *
 * <p><b>★ 另一个不变式</b>：解码出来的时长<b>永远不短于</b>编码前的请求值
 * （向上取整）。否则服务端会掐掉一个「客户端仍认为有效」的调用。</p>
 */
public final class RpcTimeout {

    // ------------------------------------------------------------------
    // gRPC: grpc-timeout: <最多 8 位数字><单位>
    // ------------------------------------------------------------------

    /** 单位对应的纳秒数，从大到小：时、分、秒、毫秒、微秒、纳秒。 */
    private static final long[] GRPC_UNITS = {
        3_600_000_000_000L, 60_000_000_000L, 1_000_000_000L, 1_000_000L, 1_000L, 1L
    };

    /** 与 {@link #GRPC_UNITS} 一一对应的单位字符（<b>大小写敏感</b>，与 grpc-java 一致）。 */
    private static final char[] GRPC_UNIT_CHARS = {'H', 'M', 'S', 'm', 'u', 'n'};

    /** grpc-timeout 的数字部分最多 8 位（gRPC 规范如此）。 */
    private static final int GRPC_MAX_DIGITS = 8;

    // ------------------------------------------------------------------
    // eRPC: 帧头 flags 的 bit1~bit7
    // ------------------------------------------------------------------

    /**
     * eRPC 超时码的基准单位：0.25 ms。
     *
     * <p>刻度是「1/4 八度」二进制：同一八度内 4 档（1、1.25、1.5、1.75 倍），
     * 八度之间按 2 倍跳 ⇒ 最大相对超调 25%。</p>
     */
    private static final long ERPC_UNIT_NANOS = 250_000L;

    /** eRPC 超时码的最大值：q=123 ⇒ e=30, m=3 ⇒ 7×2³⁰×0.25ms ≈ 21.7 天。 */
    private static final int ERPC_MAX_CODE = 124;

    private RpcTimeout() {
        // 工具类，不允许实例化
    }

    // ==================================================================
    // gRPC
    // ==================================================================

    /**
     * 把时长编码成 gRPC 的 {@code grpc-timeout} 头值。
     *
     * <p><b>选单位的规则</b>（顺序即优先级）：</p>
     * <ol>
     *   <li><b>最大的、且能整除的单位</b> —— 无损、人类友好（30 秒 → {@code "30S"}）。
     *       注意不能只看「数字塞得进 8 位」：那样 1_000_500 ns 会被编成 {@code "1H"}
     *       （超调 360 万倍，服务端会让一个客户端已放弃的调用再跑一小时）。</li>
     *   <li>纳秒单位 —— 总是整除，只在数字不超过 8 位时用。</li>
     *   <li><b>兜底</b>：最小的、向上取整后不超过 8 位的单位。超调 &lt; 1 个该单位。</li>
     * </ol>
     *
     * <p>三条路径都保证<b>解码值不短于请求值</b>（无损或向上取整）。</p>
     *
     * @param nanos 时长（纳秒）
     * @return 头值；{@code nanos <= 0} 时返回 {@code null}（表示「不带这个头」）
     */
    public static String encodeGrpcTimeout(long nanos) {
        if (nanos <= 0) {
            return null;
        }
        // 1) 最大的、且整除的单位（不含 n —— n 总是整除，留给第 2 步）
        for (int i = 0; i < GRPC_UNITS.length - 1; i++) {
            long unit = GRPC_UNITS[i];
            if (nanos % unit != 0) {
                continue;
            }
            long v = nanos / unit;
            if (v <= 99_999_999L) {
                return v + String.valueOf(GRPC_UNIT_CHARS[i]);
            }
            // 数字超了 8 位 ⇒ 这个单位用不了，继续试更小的
        }
        // 2) 纳秒单位（无损）
        if (nanos <= 99_999_999L) {
            return nanos + "n";
        }
        // 3) 兜底：最小的、向上取整后不超过 8 位的单位
        for (int i = GRPC_UNITS.length - 1; i >= 0; i--) {
            long unit = GRPC_UNITS[i];
            // 向上取整；用除法避免 nanos + unit - 1 溢出
            long v = nanos / unit + (nanos % unit == 0 ? 0 : 1);
            if (v <= 99_999_999L) {
                return v + String.valueOf(GRPC_UNIT_CHARS[i]);
            }
        }
        // 不可达：99_999_999 小时远超 long 能表示的纳秒数
        return null;
    }

    /**
     * 解析 gRPC 的 {@code grpc-timeout} 头值。
     *
     * <p>格式：最多 8 位十进制数字 + 恰好 1 个单位字符。单位<b>大小写敏感</b>
     * （{@code M} 是分钟、{@code m} 是毫秒），与 grpc-java 一致。</p>
     *
     * @param value 头值
     * @return 时长（纳秒）；缺失或非法返回 {@code -1}；数值溢出返回 {@link Long#MAX_VALUE}
     */
    public static long parseGrpcTimeoutNanos(String value) {
        if (value == null || value.isEmpty()) {
            return -1;
        }
        int len = value.length();
        int i = 0;
        long digits = 0;
        while (i < len) {
            char c = value.charAt(i);
            if (c < '0' || c > '9') {
                break;
            }
            if (i >= GRPC_MAX_DIGITS) {
                // 超过 8 位数字 ⇒ 非法
                return -1;
            }
            digits = digits * 10 + (c - '0');
            i++;
        }
        // 必须有数字，且单位字符必须是最后一个字符
        if (i == 0 || i != len - 1) {
            return -1;
        }
        long mult = grpcUnitOf(value.charAt(i));
        if (mult < 0) {
            return -1;
        }
        if (digits > Long.MAX_VALUE / mult) {
            return Long.MAX_VALUE;
        }
        return digits * mult;
    }

    private static long grpcUnitOf(char c) {
        switch (c) {
            case 'H':
                return 3_600_000_000_000L;
            case 'M':
                return 60_000_000_000L;
            case 'S':
                return 1_000_000_000L;
            case 'm':
                return 1_000_000L;
            case 'u':
                return 1_000L;
            case 'n':
                return 1L;
            default:
                return -1;
        }
    }

    // ==================================================================
    // eRPC
    // ==================================================================

    /**
     * 把时长编码成 eRPC 帧头 {@code flags} 的 7-bit 超时代码。
     *
     * <p><b>向上取整</b>：取「最小的、{@link #decodeErpcTimeoutNanos(int)} 结果不小于
     * 请求值」的码值 ⇒ 服务端拿到的剩余时间不会短于客户端请求的。</p>
     *
     * @param nanos 时长（纳秒）
     * @return 超时代码；{@code nanos <= 0} 返回 0（表示「无超时」）
     */
    public static int encodeErpcTimeoutCode(long nanos) {
        if (nanos <= 0) {
            return 0;
        }
        int lo = 1;
        int hi = ERPC_MAX_CODE;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (decodeErpcTimeoutNanos(mid) >= nanos) {
                hi = mid;
            } else {
                lo = mid + 1;
            }
        }
        return lo;
    }

    /**
     * 把 eRPC 帧头 {@code flags} 的 7-bit 超时代码解码回时长。
     *
     * @param code 超时代码
     * @return 时长（纳秒）；{@code code <= 0} 返回 0（表示「无超时」）
     */
    public static long decodeErpcTimeoutNanos(int code) {
        if (code <= 0) {
            return 0L;
        }
        int q = code - 1;
        int e = q / 4;          // 指数
        int m = q % 4;          // 归一化尾数 0..3
        return ((4L + m) << e) * ERPC_UNIT_NANOS;
    }

    /**
     * eRPC 帧头 {@code flags} 字节里 7-bit 超时代码的位偏移。
     *
     * <p>bit0 是「压缩预留」（当前恒 0），超时代码占 bit1~bit7 ⇒ 与压缩位完全隔离。</p>
     */
    public static final int ERPC_FLAG_TIMEOUT_SHIFT = 1;

    /** eRPC 帧头 {@code flags} 字节里 7-bit 超时代码的掩码。 */
    public static final int ERPC_FLAG_TIMEOUT_MASK = 0x7F;

    /**
     * 把时长编码成帧头 {@code flags} 字节 —— <b>超时代码进 bit1~bit7，bit0 恒 0</b>。
     *
     * <p>★ <b>写帧头时用这个</b>：直接拿 {@link #encodeErpcTimeoutCode(long)} 的返回值
     * 去写 {@code flags} 是错的 —— 那是「码值」，不是「flags 字节」
     * （码值要左移一位才能落在 bit1~bit7 上）。</p>
     *
     * @param nanos 时长（纳秒）
     * @return 帧头 flags；{@code nanos <= 0} 返回 0（表示「无超时」）
     */
    public static int encodeErpcFlags(long nanos) {
        return toErpcFlags(0, encodeErpcTimeoutCode(nanos));
    }

    /**
     * 把 7-bit 超时代码写进帧头 {@code flags} 字节。
     *
     * @param flags 当前的 flags
     * @param code  超时代码（见 {@link #encodeErpcTimeoutCode(long)}）
     * @return 新的 flags（bit0 恒 0）
     */
    public static int toErpcFlags(int flags, int code) {
        int mask = ERPC_FLAG_TIMEOUT_MASK << ERPC_FLAG_TIMEOUT_SHIFT;   // 0xFE —— bit1~bit7
        return (flags & ~mask) | ((code << ERPC_FLAG_TIMEOUT_SHIFT) & mask);
    }

    /**
     * 从帧头 {@code flags} 字节里取出 7-bit 超时代码。
     *
     * @param flags 帧头 flags
     * @return 超时代码（0 表示「无超时」）
     */
    public static int fromErpcFlags(int flags) {
        return (flags >>> ERPC_FLAG_TIMEOUT_SHIFT) & ERPC_FLAG_TIMEOUT_MASK;
    }

    // ==================================================================
    // 通用
    // ==================================================================

    /**
     * 把「从现在开始还剩多少毫秒」换算成 {@code nanoTime} 基准的绝对时刻。
     *
     * @param timeoutMillis 时长（毫秒）
     * @return 绝对时刻；{@code timeoutMillis <= 0} 返回 0（表示「无 deadline」）
     */
    public static long deadlineFromNow(long timeoutMillis) {
        if (timeoutMillis <= 0) {
            return 0L;
        }
        long nanos;
        try {
            nanos = Math.multiplyExact(timeoutMillis, 1_000_000L);
        } catch (ArithmeticException e) {
            // 毫秒数大到乘 1e6 就溢出 ⇒ 时长相当于无穷大
            nanos = Long.MAX_VALUE;
        }
        return deadlineFromNowNanos(nanos);
    }

    /**
     * 把「从现在开始还剩多少纳秒」换算成 {@code nanoTime} 基准的绝对时刻。
     *
     * <p>★ <b>溢出当「没有 deadline」</b>：{@code nanoTime + 时长} 溢出成负数 ⇒
     * 那个调用会被当成「一收到就过期」。而溢出的语义其实是「无穷久」
     * （比如 gRPC 头 {@code 99999999H} ≈ 1100 万年）⇒ 按「无 deadline」处理。</p>
     *
     * @param nanos 时长（纳秒）
     * @return 绝对时刻；{@code nanos <= 0} 或溢出返回 0（表示「无 deadline」）
     */
    public static long deadlineFromNowNanos(long nanos) {
        if (nanos <= 0) {
            return 0L;
        }
        try {
            return Math.addExact(System.nanoTime(), nanos);
        } catch (ArithmeticException e) {
            return 0L;
        }
    }
}
