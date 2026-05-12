package io.edap.auth.jwt.test;

import io.edap.auth.jwt.utils.Base64URL;

import java.util.UUID;

public class T {

    public static void main(String[] args) {
        String s = "{\"alg\":\"HS256\",\"typ\":\"JWT\"}";
        System.out.println(Base64URL.encode(s.getBytes()));
        System.out.println(UUID.randomUUID().toString());
    }
}
