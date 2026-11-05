/*
 * Copyright 2023 The edap Project
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

package io.edap.http.server;

import io.edap.buffer.FastBuf;

/**
 * HTTP/1.1 与 HTTP/2 的协议嗅探器（RFC 9113 §3.5）。
 *
 * <p>HTTP/2 连接前言（connection preface）是 24 字节固定串
 * {@code "PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n"}，前 3 字节是 {@code "PRI"}。
 * {@code "PRI"} 不是任何已注册的 HTTP/1.1 method，所以只看前 3 字节就能无歧义地区分两个协议。</p>
 *
 * <p><b>三条实现约束</b>：</p>
 * <ol>
 *   <li>socket read 的边界与 HTTP 消息边界无关；而且 {@code DisruptorReadDispatcher}
 *       每次 read 前都会 {@code buf.reset()}，所以「数据不足」时<b>必须</b>把已读到的字节
 *       拷出来暂存，否则这些字节会被下一次 read 冲掉（连 "PR" 都会丢）。</li>
 *   <li>判定为 HTTP/1.1 时<b>不消费</b>缓冲里的任何字节 —— 对 HTTP/1.1 路径字节级零行为变更；
 *       暂存的那 1~2 个字节由 {@link Http1Connection} 回放给 HTTP/1.1 解码器。</li>
 *   <li>判定为 HTTP/2 时暂存的字节是前言的开头，必须交给 HTTP/2 连接继续比对。</li>
 * </ol>
 */
public class HttpProtocolSniffer {

    public enum Protocol {
        HTTP_1_1,
        HTTP_2
    }

    /** h2 连接前言的前 3 字节 */
    private static final byte[] H2_PREFIX = {'P', 'R', 'I'};

    /** 判定所需的最少字节数 */
    private static final int PREFIX_LEN = H2_PREFIX.length;

    /** 跨 read 暂存的前缀字节（最多 PREFIX_LEN - 1 = 2 个） */
    private final byte[] carry = new byte[PREFIX_LEN - 1];
    private int carryLen;

    /**
     * 嗅探协议。
     *
     * @param buf 本次 read 的缓冲（当前读位置起是未处理的数据）
     * @return 判定结果；{@code null} 表示数据不足，需要等下一次 read
     */
    public Protocol detect(FastBuf buf) {
        int total = carryLen + buf.remain();
        if (total < PREFIX_LEN) {
            for (int i = 0; i < total; i++) {
                if (peek(buf, i) != H2_PREFIX[i]) {
                    // 已经能确定不是 h2（"PRI" 不是合法 HTTP/1.1 method）
                    return Protocol.HTTP_1_1;
                }
            }
            // 仍是 "PRI" 的前缀 —— 数据不足。把 buf 里的字节挪进 carry，等下一次 read。
            // （这些字节被消费是必须的：dispatcher 下一次 read 前会 buf.reset()，不拷走就丢了。）
            while (buf.remain() > 0) {
                carry[carryLen++] = buf.get();
            }
            return null;
        }
        for (int i = 0; i < PREFIX_LEN; i++) {
            if (peek(buf, i) != H2_PREFIX[i]) {
                return Protocol.HTTP_1_1;
            }
        }
        return Protocol.HTTP_2;
    }

    /**
     * 读取第 i 个字节（不消费）。i &lt; carryLen 时取暂存区，否则取缓冲当前读位置之后的字节。
     */
    private byte peek(FastBuf buf, int i) {
        if (i < carryLen) {
            return carry[i];
        }
        return buf.get(buf.rpos() + (i - carryLen));
    }

    /**
     * 取出暂存的字节（拷贝，不清空）。
     *
     * <p>判定为 HTTP/1.1 时这些字节必须回放给 HTTP/1.1 解码器；
     * 判定为 HTTP/2 时它们是连接前言的开头，必须交给 HTTP/2 连接继续比对。</p>
     */
    public byte[] getCarry() {
        byte[] out = new byte[carryLen];
        System.arraycopy(carry, 0, out, 0, carryLen);
        return out;
    }

    public int getCarryLen() {
        return carryLen;
    }
}
