package io.edap.auth.jwt.utils;

import io.edap.json.JsonWriter;
import io.edap.util.ByteArrayBuilder;
import io.edap.util.StringUtil;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.util.Arrays;
import java.util.Base64;

public class Base64URL {

    private static final byte[] ALPHABET = new byte[]{
            'A', 'B', 'C', 'D', 'E', 'F', 'G', 'H', 'I', 'J', 'K', 'L', 'M',
            'N', 'O', 'P', 'Q', 'R', 'S', 'T', 'U', 'V', 'W', 'X', 'Y', 'Z',
            'a', 'b', 'c', 'd', 'e', 'f', 'g', 'h', 'i', 'j', 'k', 'l', 'm',
            'n', 'o', 'p', 'q', 'r', 's', 't', 'u', 'v', 'w', 'x', 'y', 'z',
            '0', '1', '2', '3', '4', '5', '6', '7', '8', '9', '-', '_'
    };

    private static final int[] VALUES = new int[128];

    private static final MethodHandle ENCODE_0;
    private static final MethodHandle DECODE_0;
    public  static final boolean       USE_JDK;

    static {
        MethodHandle encMh = null;
        MethodHandle decMh = null;
        boolean ok = false;
        try {
            // findVirtual with a plain `MethodHandles.lookup()` cannot resolve
            // a private method of `java.util.Base64` from an unnamed module —
            // IllegalAccessError even with --add-opens. Fix: setAccessible +
            // unreflect, which works across modules when --add-opens is set.
            java.lang.reflect.Method encMethod =
                    Base64.Encoder.class.getDeclaredMethod("encode0",
                            byte[].class, int.class, int.class, byte[].class);
            encMethod.setAccessible(true);
            encMh = MethodHandles.lookup().unreflect(encMethod)
                    .bindTo(Base64.getUrlEncoder().withoutPadding());

            java.lang.reflect.Method decMethod =
                    Base64.Decoder.class.getDeclaredMethod("decode0",
                            byte[].class, int.class, int.class, byte[].class);
            decMethod.setAccessible(true);
            decMh = MethodHandles.lookup().unreflect(decMethod)
                    .bindTo(Base64.getUrlDecoder());

            ok = verify(encMh, decMh);
        } catch (Throwable t) {
            ok = false;
        }
        ENCODE_0 = ok ? encMh  : null;
        DECODE_0 = ok ? decMh  : null;
        USE_JDK  = ok;

        for (int i = 0; i < VALUES.length; i++) {
            VALUES[i] = -1;
        }
        for (int i=0;i<ALPHABET.length;i++) {
            VALUES[ALPHABET[i]] = i;
        }
    }

    public static String encodeToString(byte[] src) {
        byte[] dst = new byte[encodedLen(src.length)];
        encode(src, 0, src.length, dst);
        return StringUtil.fastInstance(dst, (byte)0);
    }

    /**
     * Encode {@code src[off..off+len]} as URL-safe base64 (no padding), write
     * into {@code scratch[0..]}. Returns bytes written.
     *
     * <p>{@code scratch.length} must be {@code >= encodedLen(len)}.
     */
    public static int encode(byte[] src, int off, int len, byte[] scratch) {
        if (USE_JDK) {
            try {
                // invokeExact on an int-returning MethodHandle requires the
                // call site to declare the int return type, otherwise
                // WrongMethodTypeException.
                return (int) ENCODE_0.invokeExact(src, off, off + len, scratch);
            } catch (Throwable t) {
                // fallthrough to fallback
            }
        }
        return simpleEncode(src, off, len, scratch);
    }

    public static byte[] decode(byte[] src) {
        return Base64.getDecoder().decode(src);
    }
    /**
     * Decode {@code src[off..off+len]} from URL-safe base64 (no padding),
     * write into {@code scratch[0..]}. Returns bytes written.
     *
     * <p>{@code scratch.length} must be {@code >= decodedMaxLen(len)}.
     */
    public static int decode(byte[] src, int off, int len, byte[] scratch) {
        if (USE_JDK) {
            try {
                return (int) DECODE_0.invokeExact(src, off, off + len, scratch);
            } catch (Throwable t) {
                // fallthrough
            }
        }
        return simpleDecode(src, off, len, scratch);
    }

    private static int simpleEncode(byte[] src, int off, int len, byte[] dst) {
        byte[]  alph = ALPHABET;
        int mod = len % 3;
        int end = len - mod;
        int i = off;
        int count = 0;
        int v;
        while (i < off + end) {
            v = (src[i] & 0xFF) << 16 | (src[i + 1] & 0xFF) << 8 | (src[i + 2] & 0xFF);
            dst[count]     = alph[(v >> 18) & 0x3f];
            dst[count + 1] = alph[(v >> 12) & 0x3f];
            dst[count + 2] = alph[(v >> 6)  & 0x3f];
            dst[count + 3] = alph[v & 0x3f];
            i += 3;
            count += 4;
        }
        if (mod == 1) {
            int b1 = src[i] & 0xFF;
            dst[count]     = alph[(b1 >> 2) & 0x3f];
            dst[count + 1] = alph[(b1 << 4) & 0x3f];
            count += 2;
        } else if (mod == 2) {
            int b1 = src[i] & 0xFF;
            int b2 = src[i + 1] & 0xFF;
            dst[count]     = alph[(b1 >> 2) & 0x3f];
            dst[count + 1] = alph[((b1 << 4) | (b2 >> 4)) & 0x3f];
            dst[count + 2] = alph[(b2 << 2) & 0x3f];
            count += 3;
        }
        return count;
    }

    private static int simpleDecode(byte[] src, int off, int len, byte[] dst) {
        int mod = len % 4;
        int end = len - mod;
        int i = off;
        int count = 0;
        int v;
        while (i < off + end) {
            v = (VALUES[src[i]     & 0x7F] & 0x3F) << 18
                    | (VALUES[src[i + 1] & 0x7F] & 0x3F) << 12
                    | (VALUES[src[i + 2] & 0x7F] & 0x3F) << 6
                    | (VALUES[src[i + 3] & 0x7F] & 0x3F);
            dst[count]     = (byte) (v >>> 16);
            dst[count + 1] = (byte) (v >>> 8);
            dst[count + 2] = (byte) v;
            i += 4;
            count += 3;
        }
        if (mod == 2) {
            v = (VALUES[src[i]     & 0x7F] & 0x3F) << 2
                    | (VALUES[src[i + 1] & 0x7F] & 0x3F) >> 4;
            dst[count] = (byte) v;
            count += 1;
        } else if (mod == 3) {
            v = (VALUES[src[i]     & 0x7F] & 0x3F) << 10
                    | (VALUES[src[i + 1] & 0x7F] & 0x3F) << 4
                    | (VALUES[src[i + 2] & 0x7F] & 0x3F) >> 2;
            dst[count]     = (byte) (v >>> 8);
            dst[count + 1] = (byte) v;
            count += 2;
        }
        return count;
    }

    /**
     * 验证反射调用jdk内部方法是否是正确的base64方法，如果正确则使用反射对方法，如果不正确使用自己写的base64的编解码
     * @param encMh 反射的编码方法的MethodHandle实例
     * @param decMh 反射对解码方法的MethodHandle实例
     * @return 是否为正确base64编解码方法
     */
    private static boolean verify(MethodHandle encMh, MethodHandle decMh) {
        try {
            // Test vectors — picked to hit every interesting path:
            //   empty, all tail sizes mod 3, alphabet boundary bytes ('-' and '_'),
            //   high-bit patterns, multi-block, all-0xFF.
            byte[][] inputs = {
                    new byte[0],                                                 // empty
                    { (byte) 0xFF },                                             // mod 3 == 1
                    { (byte) 0xFF, (byte) 0xFE },                                // mod 3 == 2
                    { (byte) 0xFF, (byte) 0xFE, (byte) 0xFD },                   // mod 3 == 0
                    { 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11 },                       // 11 B (mod 3 == 2)
                    { 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12 },                   // 12 B (mod 3 == 0)
                    fillRandom(100,  0xC0FFEE_1L),                               // 100 B random → URL-safe boundary bytes
                    fillRandom(800,  0xDEADBEEFL),                               // 800 B
                    fillRandom(2000, 0xCAFEBABEL),                               // 2000 B
                    fillConst(100, (byte) 0xFF),                                 // high-bit pattern
            };

            for (byte[] input : inputs) {
                int encLen = encodedLen(input.length);

                // --- encode path: reflective vs self-fallback ---
                byte[] tmpEnc    = new byte[encLen];
                int    encWritten = (int) encMh.invokeExact(input, 0, input.length, tmpEnc);
                byte[] oracleEnc  = new byte[encLen];
                int    oracleLen  = simpleEncode(input, 0, input.length, oracleEnc);
                if (encWritten != oracleLen
                        || !Arrays.equals(tmpEnc, 0, oracleLen, oracleEnc, 0, oracleLen)) {
                    return false;
                }

                // --- decode path: reflective vs self-fallback, with round-trip ---
                byte[] tmpDec = new byte[input.length];
                int written = (int) decMh.invokeExact(oracleEnc, 0, oracleLen, tmpDec);
                if (written != input.length
                        || !Arrays.equals(tmpDec, 0, written, input, 0, input.length)) {
                    return false;
                }
            }
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 通过 {@link JdkBase64Bridge} 的反射路径编码，caller 拥有 scratch。
     * <p>
     * 对比 {@link #encodeTo(ByteArrayBuilder, byte[])}（直接写）：
     * <ul>
     *   <li>快 3-6×（aarch64 NEON / x86 SSSE3 SIMD）</li>
     *   <li>需要 caller 提供 scratch（大小 ≥ {@link JdkBase64Bridge#encodedLen(int)}）</li>
     *   <li>首次调用前会做反射 init-verify，失败时永久 fallback 到查表实现</li>
     * </ul>
     * <p>
     * 部署需要 {@code --add-opens java.base/java.util=ALL-UNNAMED}（JDK 17+），
     * 缺失时 {@link JdkBase64Bridge#USE_JDK} 为 false，自动走 fallback，性能等同于查表。
     */
    public static void encodeTo(ByteArrayBuilder builder, byte[] bytes, byte[] scratch) {
        int written = encode(bytes, 0, bytes.length, scratch);
        builder.ensureCapacity(builder.length() + written);
        System.arraycopy(scratch, 0, builder.getValue(), builder.length(), written);
        builder.setLength(builder.length() + written);
    }

    /**
     * 通过 {@link JdkBase64Bridge} 编码 JsonWriter 中的 JSON 字节，caller 拥有 scratch。
     * <p>
     * JsonWriter 重载版本，等价于
     * {@code encodeTo(builder, writer.getBuf(), scratch)}（截取 writer.size() 长度）。
     */
    public static void encodeTo(ByteArrayBuilder builder, JsonWriter writer, byte[] scratch) {
        int len = writer.size();
        int written = encode(writer.getBuf(), 0, len, scratch);
        builder.ensureCapacity(builder.length() + written);
        System.arraycopy(scratch, 0, builder.getValue(), builder.length(), written);
        builder.setLength(builder.length() + written);
    }

    private static byte[] fillRandom(int n, long seed) {
        byte[] b = new byte[n];
        java.util.Random r = new java.util.Random(seed);
        r.nextBytes(b);
        return b;
    }

    private static byte[] fillConst(int n, byte v) {
        byte[] b = new byte[n];
        java.util.Arrays.fill(b, v);
        return b;
    }

    /**
     * URL-safe no-padding encoded length for {@code len} input bytes.
     */
    public static int encodedLen(int len) {
        int full = len / 3;
        int tail = len % 3;
        return full * 4 + (tail > 0 ? tail + 1 : 0);
    }

    /** Maximum decoded length (assumes no padding chars, last group may be 2 or 3 chars). */
    public static int decodedMaxLen(int len) {
        int full = len / 4;
        int tail = len % 4;
        if (tail == 0) return full * 3;
        if (tail == 1) return -1;             // invalid — but we still need an upper bound
        if (tail == 2) return full * 3 + 1;
        return full * 3 + 2;                  // tail == 3
    }
}
