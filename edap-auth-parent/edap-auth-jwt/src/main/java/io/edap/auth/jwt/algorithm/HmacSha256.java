package io.edap.auth.jwt.algorithm;

import io.edap.auth.jwt.Algorithm;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;

public class HmacSha256 implements Algorithm {

    final Mac mac;

    public HmacSha256(String key) {
        try {
            mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
    @Override
    public byte[] sign(byte[] data, int offset, int len) {
        mac.reset();
        mac.update(data, offset, len);
        return mac.doFinal();
    }
}
