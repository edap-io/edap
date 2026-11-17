package io.edap.jni.crypto;

import io.edap.jni.Native;

/**
 * 流式摘要 / MAC native 实现（SHA-256 / SHA-1 / MD5 / HMAC-SHA256 / HMAC-SHA1）。
 *
 * <p>作为 {@code io.edap.crypto.digest.NativeDigestProvider} 与
 * {@code io.edap.crypto.mac.NativeMacProvider} 的底层：edap-crypto 不硬依赖
 * edap-native（test scope），通过 {@code NativeBridge} 反射绑定这里的三个入口。</p>
 *
 * <p><b>★ 为什么要有流式入口</b>：早先的 native 摘要只有一次性 API
 * （{@link NativeDigest#digestSha2560(byte[], int, int)}），流式语义只能在
 * Java 侧用 {@code ByteAccumulator} 累积 —— 整份数据拷一遍，等摘要算完再交给 native。
 * 实测（Solaris/SPARC，Oracle JDK 17）：64KB 载荷光这次累积就要
 * <b>~17.7µs</b>（一次 64KB 分配 + 一次 64KB arraycopy），与一次 SHA-256 本体同量级。
 * ⇒ 改成 native 侧持有摘要上下文，数据喂一次算一次 ⇒ 这笔拷贝直接消失。</p>
 *
 * <p><b>会话形状</b>：{@code update* → finish}，与 edap-crypto 的
 * {@code DigestSession} / {@code MacSession} 一一对应：</p>
 * <ul>
 *   <li>{@link #update} —— 追加数据；<b>句柄为 0 时顺便把上下文建出来</b>
 *       （{@code alg} / {@code key} 只在这一刻有意义）；</li>
 *   <li>{@link #finish} —— 算出结果，<b>并释放上下文</b>（句柄随即失效）；</li>
 *   <li>{@link #free} —— 不算结果直接释放（放弃 / 异常路径）。</li>
 * </ul>
 *
 * <p><b>★ 为什么把「建上下文」并进「追加数据」</b>：JNI 调用本身不便宜 ——
 * 在 Solaris/SPARC 上一次 native 调用是微秒级量级，而一次 64KB 的 SHA-256 才 ~90µs。
 * 分成 {@code init} + {@code update} 两个入口 ⇒ 每次操作多一次 JNI 往返。
 * 并进一次调用后，「喂一次数据、收一次尾」只要 <b>2 次</b> native 调用
 * （旧的一次性 API 是 1 次，流式化只多 1 次）。</p>
 *
 * <p><b>★ 调用方约定</b>：{@code finish} 之后<b>不能再</b> {@code free}
 * —— {@code finish} 已经把上下文释放掉了。</p>
 *
 * <p><b>算法编号用 int 而不是字符串</b>：每次调用 {@code GetStringUTFChars}
 * 在 Solaris/SPARC 上约 9.5µs，是 native 摘要固定成本的大头
 * （见 {@link NativeDigest} 的说明）。</p>
 *
 * <p><b>Fallback</b>：{@link Native#ENABLE_NATIVE} 为 false 时 native 不可用，
 * 调用方应改走 JCE。</p>
 */
public class NativeStream {

    static {
        Native.loadLibrary();
    }

    /**
     * 算法编号 —— SHA-256。
     */
    public static final int ALG_SHA256 = 0;

    /**
     * 算法编号 —— SHA-1。
     */
    public static final int ALG_SHA1 = 1;

    /**
     * 算法编号 —— MD5。
     */
    public static final int ALG_MD5 = 2;

    /**
     * 算法编号 —— HMAC-SHA256。
     */
    public static final int ALG_HMAC_SHA256 = 3;

    /**
     * 算法编号 —— HMAC-SHA1。
     */
    public static final int ALG_HMAC_SHA1 = 4;

    /**
     * 追加一段数据；<b>{@code ctx == 0} 时顺便把上下文建出来</b>。
     *
     * <p><b>必须是 public</b>：{@code io.edap.crypto.internal.NativeBridge} 用
     * {@code MethodHandles.lookup().findStatic(...)} 绑定它，跨包只能访问 public 成员。</p>
     *
     * @param ctx    上下文句柄；0 表示还没有上下文，本次调用会建一个
     * @param alg    算法编号（{@link #ALG_SHA256} 等）；只在 {@code ctx == 0} 时用
     * @param key    HMAC 的 key（只在 {@code ctx == 0} 时用）；纯摘要传 {@code null}
     * @param keyLen key 长度（字节）；只在 {@code ctx == 0} 时用
     * @param data   数据
     * @param offset 起始偏移
     * @param len    长度
     * @return 上下文句柄（已存在则原样返回）；失败返回 0
     */
    public static native long update(long ctx, int alg, byte[] key, int keyLen,
            byte[] data, int offset, int len);

    /**
     * 算出结果并<b>释放上下文</b>（句柄随即失效，不能再 {@link #free}）。
     *
     * @param ctx 上下文句柄
     * @return 摘要 / MAC 字节
     */
    public static native byte[] finish(long ctx);

    /**
     * 放弃上下文（不算结果直接释放）。
     *
     * @param ctx 上下文句柄
     */
    public static native void free(long ctx);
}
