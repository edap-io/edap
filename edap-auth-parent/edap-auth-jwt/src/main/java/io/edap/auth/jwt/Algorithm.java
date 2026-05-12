package io.edap.auth.jwt;

public interface Algorithm {

    byte[] sign(byte[] data, int offset, int len);
}
