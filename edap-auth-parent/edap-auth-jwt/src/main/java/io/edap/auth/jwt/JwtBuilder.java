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

import static io.edap.auth.jwt.JWT.ALGORITHM_CACHE;

public class JwtBuilder {

    private Map<String, Object> payload = new HashMap<>();
    private static final byte[] DEFAULT_HEADER = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9".getBytes();
    private static final Map<String, byte[]> HEADER_CACHE = new HashMap<>();
    private String algorithm;
    private String keyId;
    private String signKey;
    private byte[] header;

    /**
     * 本地线程的ProtoBuf的Writer减少内存分配次数
     */
    public static final ThreadLocal<ByteArrayBuilder> THREAD_WRITER;

    /**
     * 本地线程的ProtoBuf的Writer减少内存分配次数
     */
    public static final ThreadLocal<JsonWriter> JSON_WRITER;

    static {
        THREAD_WRITER = ThreadLocal.withInitial(() -> new ByteArrayBuilder());
        JSON_WRITER = ThreadLocal.withInitial(() -> new ByteArrayJsonWriter(new ByteArrayBufOut()));
    }

    public JwtBuilder() {
        this.algorithm = "HS256";
        this.header    = DEFAULT_HEADER;
    }

    public JwtBuilder(String algorithm, String type, String keyId) {
        this.algorithm = algorithm;
        this.keyId     = keyId;
        if ("HS256".equals(algorithm) && "JWT".equals(type) && StringUtil.isEmpty(keyId)) {
            header = DEFAULT_HEADER;
        } else {
            String headerKey = "{\"alg\":\"" + algorithm + "\",\"typ\":\"" + type + "\"";
            if (StringUtil.isEmpty(keyId)) {
                headerKey += "}";
            } else {
                headerKey += ",\"kid\":\"" + keyId + "\"}";
            }
            byte[] headerBytes = HEADER_CACHE.get(headerKey);
            if (headerBytes == null) {
                headerBytes = Base64URL.encode(headerKey.getBytes()).getBytes();
                HEADER_CACHE.put(headerKey, headerBytes);
                header = headerBytes;
            }
        }
    }

    public JwtBuilder subject(String subject) {
        payload.put("sub", subject);

        return this;
    }

    public JwtBuilder issuer(String issuer) {
        payload.put("iss", issuer);

        return this;
    }

    public JwtBuilder audience(String audience) {
        payload.put("aud", audience);

        return this;
    }

    public JwtBuilder expiresAt(long expiresAt) {
        payload.put("exp", expiresAt);

        return this;
    }

    public JwtBuilder notBefore(long notBefore) {
        payload.put("nbf", notBefore);

        return this;
    }

    public JwtBuilder issuedAt(long issuedAt) {
        payload.put("iat", issuedAt);

        return this;
    }

    public JwtBuilder jwtId(String jwtId) {
        payload.put("jti", jwtId);

        return this;
    }

    public JwtBuilder claim(String name, Object value) {
        payload.put(name, value);

        return this;
    }

    public JwtBuilder signWith(String signKey) {
        this.signKey = signKey;
        return this;
    }

    public String build() {
        ByteArrayBuilder byteBuilder = THREAD_WRITER.get();
        byteBuilder.reset();
        JsonWriter jsonWriter = JSON_WRITER.get();
        jsonWriter.reset();
        byteBuilder.append(header, 0, header.length);
        byteBuilder.append((byte)'.');
        jsonWriter.reset();
        Eson.serialize(payload, jsonWriter);
        Base64URL.encodeTo(byteBuilder, jsonWriter);
        if ("HS256".equals(algorithm)) {
            String key = algorithm + ":" + signKey;
            Algorithm algorithm = ALGORITHM_CACHE.get(key);
            if (algorithm == null) {
                algorithm = new HmacSha256(signKey);
                ALGORITHM_CACHE.put(key, algorithm);
            }
            byte[] sign = algorithm.sign(byteBuilder.getValue(), 0, byteBuilder.length());
            byteBuilder.append((byte)'.');
            Base64URL.encodeTo(byteBuilder, sign);
        }
        byte[] data = new byte[byteBuilder.length()];
        System.arraycopy(byteBuilder.getValue(), 0, data, 0, data.length);
        return StringUtil.fastInstance(data, (byte)0);
    }
}
