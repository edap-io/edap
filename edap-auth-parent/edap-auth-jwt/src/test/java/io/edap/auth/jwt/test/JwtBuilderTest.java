package io.edap.auth.jwt.test;

import io.edap.auth.jwt.JWT;
import io.edap.auth.jwt.JwtBuilder;
import io.edap.auth.jwt.VerifyResult;
import io.edap.auth.jwt.utils.Base64URL;
import io.edap.json.Eson;
import io.edap.json.JsonObject;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class JwtBuilderTest {

    @Test
    public void testBuilder() {
        String jwtId = UUID.randomUUID().toString();
        JwtBuilder builder = JWT.create()
                .subject("edap")
                .issuer("edap-issuer")
                .audience("edap-audience")
                .expiresAt(System.currentTimeMillis() + 1000 * 60 * 60 * 24 * 7)
                .notBefore(System.currentTimeMillis())
                .issuedAt(System.currentTimeMillis())
                .jwtId(jwtId)
                .claim("name", "edap")
                .claim("age", 18);
        String jwt = builder.signWith("edap-secret").build();
        System.out.println(jwt);
        String header = jwt.substring(0, jwt.indexOf( "."));
        assertEquals("eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9", header);
        String payload = jwt.substring(jwt.indexOf( ".") + 1, jwt.lastIndexOf("."));
        JsonObject jsonPayload = Eson.parseJsonObject(Base64.getUrlDecoder().decode(payload.getBytes(StandardCharsets.UTF_8)));
        assertEquals("edap", jsonPayload.getString("sub"));

        jwt = builder.signWith("edap-secret").build();
        System.out.println(jwt);
        header = jwt.substring(0, jwt.indexOf( "."));
        assertEquals("eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9", header);
        payload = jwt.substring(jwt.indexOf( ".") + 1, jwt.lastIndexOf("."));
        jsonPayload = Eson.parseJsonObject(Base64URL.decode(payload));
        assertEquals("edap", jsonPayload.getString("sub"));

        VerifyResult result = JWT.verify(jwt, "edap-secret");
        assertEquals(0, result.getCode());

        result = JWT.verify(jwt + "1", "edap-secret");
        assertEquals(2, result.getCode());
        assertEquals("签名错误", result.getMessage());
    }
}
