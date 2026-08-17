package io.edap.auth.jwt;

import io.edap.auth.jwt.Algorithm;
import io.edap.auth.jwt.JwtBuilder;
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

/**
 * {@link JwtBuilder} 默认实现（HS256）。
 *
 * <p><b>线程不安全</b>：一个实例应当只在单线程内完成
 * {@code create() → setXxx() → build()} 全过程；不同线程请各自 {@code new DefaultJwtBuilder()}。
 * 共享实例并发修改 payload HashMap 会导致 NPE / 死循环（HashMap 非线程安全）。</p>
 *
 * <p>静态字段（{@link #THREAD_WRITER} / {@link #JSON_WRITER} / {@link #HEADER_CACHE}）均为线程安全；
 * {@link #ALGORITHM_CACHE}（KeyCache 实例）也线程安全。</p>
 */
public class DefaultJwtBuilder implements JwtBuilder {

    private Map<String, Object> payload = new HashMap<>();
    private static final byte[] DEFAULT_HEADER = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9".getBytes();
    private static final ConcurrentMap<String, byte[]> HEADER_CACHE = new ConcurrentHashMap<>();
    private final String algorithm;
    private final String keyId;
    private String signKey;
    private final byte[] header;

    /** 本地线程的 ByteArrayBuilder 减少内存分配 */
    public static final ThreadLocal<ByteArrayBuilder> THREAD_WRITER;

    /** 本地线程的 JsonWriter 减少内存分配 */
    public static final ThreadLocal<JsonWriter> JSON_WRITER;

    static {
        THREAD_WRITER = ThreadLocal.withInitial(() -> new ByteArrayBuilder());
        JSON_WRITER = ThreadLocal.withInitial(() -> new ByteArrayJsonWriter(new ByteArrayBufOut()));
    }

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
            byte[] headerBytes = HEADER_CACHE.computeIfAbsent(headerKey,
                    k -> Base64URL.encode(k.getBytes()).getBytes());
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
        ByteArrayBuilder byteBuilder = THREAD_WRITER.get();
        byteBuilder.reset();
        JsonWriter jsonWriter = JSON_WRITER.get();
        jsonWriter.reset();
        byteBuilder.append(header, 0, header.length);
        byteBuilder.append((byte)'.');
        // payload 用 TreeMap 包装后序列化：相同 claims 跨 JVM 跨进程产生相同 key 顺序，
        // 保证签名输入可重现（HashMap 顺序不一致会导致相同逻辑 token 产生不同签名）
        Eson.serialize(new TreeMap<>(payload), jsonWriter);
        Base64URL.encodeTo(byteBuilder, jsonWriter);
        if ("HS256".equals(algorithm)) {
            Algorithm alg = ALGORITHM_CACHE.getOrCreate("HS256", signKey, HmacSha256::new);
            byte[] sign = alg.sign(byteBuilder.getValue(), 0, byteBuilder.length());
            byteBuilder.append((byte)'.');
            Base64URL.encodeTo(byteBuilder, sign);
        }
        byte[] data = new byte[byteBuilder.length()];
        System.arraycopy(byteBuilder.getValue(), 0, data, 0, data.length);
        return StringUtil.fastInstance(data, (byte)0);
    }
}
