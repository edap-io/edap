package io.edap.auth.jwt.test;

import io.edap.auth.jwt.utils.Base64URL;

import java.nio.charset.CharsetEncoder;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

public class T {

    public static void main(String[] args) {
        String s = "{\"alg\":\"HS256\",\"typ\":\"JWT\"}";
        System.out.println(Base64URL.encodeToString(s.getBytes()));
        System.out.println(UUID.randomUUID().toString());


        CharsetEncoder encoder = StandardCharsets.UTF_8.newEncoder();
        s.getBytes(StandardCharsets.UTF_8);
    }
}
