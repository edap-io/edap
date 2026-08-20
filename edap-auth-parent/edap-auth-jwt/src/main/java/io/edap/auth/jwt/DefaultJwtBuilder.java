package io.edap.auth.jwt;

import io.edap.auth.jwt.algorithm.HmacSha256;
import io.edap.auth.jwt.utils.Base64URL;
import io.edap.io.ByteArrayBufOut;
import io.edap.json.Eson;
import io.edap.json.JsonWriter;
import io.edap.json.writer.ByteArrayJsonWriter;
import io.edap.util.ByteArrayBuilder;
import io.edap.util.StringUtil;

import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import static io.edap.auth.jwt.JWT.ALGORITHM_CACHE;
import static io.edap.auth.jwt.utils.Base64URL.encode;
import static io.edap.auth.jwt.utils.Base64URL.encodedLen;

/**
 * {@link JwtBuilder} 默认实现（HS256）。
 *
 * <p><b>线程不安全</b>：一个实例应当只在单线程内完成
 * {@code create() → setXxx() → build()} 全过程；不同线程请各自 {@code new DefaultJwtBuilder()}。
 * 共享实例并发修改 payload HashMap 会导致 NPE / 死循环（HashMap 非线程安全）。</p>
 *
 * <p>{@link #THREAD_CTX}（{@link JwtBuildContext} 实例）线程安全，
 * {@link #HEADER_CACHE}（ConcurrentMap）和 {@link #ALGORITHM_CACHE}（KeyCache 实例）也线程安全。</p>
 */
public class DefaultJwtBuilder implements JwtBuilder {

    private Map<String, Object> payload = new HashMap<>();
    private static final byte[] DEFAULT_HEADER = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9".getBytes();
    private static final ConcurrentMap<String, byte[]> HEADER_CACHE = new ConcurrentHashMap<>();
    private final String algorithm;
    private final String keyId;
    private String signKey;
    private final byte[] header;

    /**
     * 本地线程的 JWT 构建上下文。把 builder / jsonWriter / base64 scratch
     * 三个资源绑在一起，{@code build()} 只需一次 ThreadLocal.get()。
     *
     * <p>scratch 起始 256 B，按需 2× 扩容（懒分配）。扩容成本是一次 byte[] 分配 + 旧 buffer GC，
     * 之后同线程所有 build() 复用，零分配。</p>
     */
    public static final ThreadLocal<JwtBuildContext> THREAD_CTX =
            ThreadLocal.withInitial(JwtBuildContext::new);

    public DefaultJwtBuilder() {
        this.algorithm = "HS256";
        this.keyId = null;
        this.header = DEFAULT_HEADER;
    }

    public DefaultJwtBuilder(String algorithm, String type, String keyId) {
        this.algorithm = algorithm;
        this.keyId = keyId;
        if ("HS256".equals(algorithm) && "JWT".equals(type) && StringUtil.isEmpty(keyId)) {
            header = DEFAULT_HEADER;
        } else {
            String headerKey = "{\"alg\":\"" + algorithm + "\",\"typ\":\"" + type + "\"";
            if (StringUtil.isEmpty(keyId)) {
                headerKey += "}";
            } else {
                headerKey += ",\"kid\":\"" + keyId + "\"}";
            }
            // 直接写 HEADER_CACHE，避免 encode → String → byte[] 来回转（一次性缓存，影响忽略）。
            byte[] headerBytes = HEADER_CACHE.computeIfAbsent(headerKey, k -> {
                byte[] json = k.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
                int encLen = encodedLen(json.length);
                byte[] out  = new byte[encLen];
                encode(json, 0, json.length, out);
                return out;
            });
            header = headerBytes;
        }
    }

    @Override
    public JwtBuilder subject(String subject) {
        payload.put("sub", subject);
        return this;
    }

    @Override
    public JwtBuilder issuer(String issuer) {
        payload.put("iss", issuer);
        return this;
    }

    @Override
    public JwtBuilder audience(String audience) {
        payload.put("aud", audience);
        return this;
    }

    @Override
    public JwtBuilder expiresAt(long expiresAt) {
        payload.put("exp", expiresAt);
        return this;
    }

    @Override
    public JwtBuilder notBefore(long notBefore) {
        payload.put("nbf", notBefore);
        return this;
    }

    @Override
    public JwtBuilder issuedAt(long issuedAt) {
        payload.put("iat", issuedAt);
        return this;
    }

    @Override
    public JwtBuilder jwtId(String jwtId) {
        payload.put("jti", jwtId);
        return this;
    }

    @Override
    public JwtBuilder claim(String name, Object value) {
        payload.put(name, value);
        return this;
    }

    @Override
    public JwtBuilder signWith(String signKey) {
        this.signKey = signKey;
        return this;
    }

    @Override
    public String build() {
        JwtBuildContext ctx = THREAD_CTX.get();
        ByteArrayBuilder byteBuilder = ctx.builder;
        JsonWriter jsonWriter = ctx.jsonWriter;
        byteBuilder.reset();
        jsonWriter.reset();

        byteBuilder.append(header, 0, header.length);
        byteBuilder.append((byte) '.');

        // payload 用 TreeMap 包装后序列化：相同 claims 跨 JVM 跨进程产生相同 key 顺序，
        // 保证签名输入可重现（HashMap 顺序不一致会导致相同逻辑 token 产生不同签名）
        Eson.serialize(new TreeMap<>(payload), jsonWriter);

        // payload 编码：scratch 按 payload 大小扩容一次，后续所有 build() 复用
        int jsonSize = jsonWriter.size();
        byte[] scratch = ctx.ensureBase64Scratch(encodedLen(jsonSize));
        Base64URL.encodeTo(byteBuilder, jsonWriter, scratch);

        if ("HS256".equals(algorithm)) {
            Algorithm alg = ALGORITHM_CACHE.getOrCreate("HS256", signKey, HmacSha256::new);
            byte[] sign = alg.sign(byteBuilder.getValue(), 0, byteBuilder.length());
            byteBuilder.append((byte) '.');
            // signature 固定 32 B → ~43 B 编码，远小于 payload scratch，零再扩容
            Base64URL.encodeTo(byteBuilder, sign, scratch);
        }

        byte[] data = new byte[byteBuilder.length()];
        System.arraycopy(byteBuilder.getValue(), 0, data, 0, data.length);
        return StringUtil.fastInstance(data, (byte) 0);
    }

    /**
     * 本地线程的 JWT 构建上下文：bundles builder / jsonWriter / base64 scratch，
     * 让 {@link #build()} 只用一次 {@code ThreadLocal.get()} 而不是三次。
     *
     * <p>scratch 字段可变：{@link #ensureBase64Scratch(int)} 按需扩容后写回字段，
     * 调用方应每次 build() 重新拿一次 {@code ctx.ensureBase64Scratch(needed)}
     * （而不是缓存返回值），这样扩容才会生效。</p>
     */
    public static final class JwtBuildContext {

        private static final int INITIAL_SCRATCH_BYTES = 256;

        final ByteArrayBuilder builder    = new ByteArrayBuilder();
        final JsonWriter       jsonWriter = new ByteArrayJsonWriter(new ByteArrayBufOut());

        /** caller-owned scratch；多次 build() 之间复用，按 2× 扩容。 */
        byte[] base64Scratch = new byte[INITIAL_SCRATCH_BYTES];

        /**
         * 返回长度 {@code >= needed} 的 scratch，不够时按 2× 扩容。
         * 返回的引用可能跟本对象内部的 {@link #base64Scratch} 不同（扩容时换了新数组），
         * 所以调用方每次都需要重新调用本方法取最新引用。
         */
        public byte[] ensureBase64Scratch(int needed) {
            if (base64Scratch.length < needed) {
                base64Scratch = new byte[Math.max(needed, base64Scratch.length * 2)];
            }
            return base64Scratch;
        }
    }
}