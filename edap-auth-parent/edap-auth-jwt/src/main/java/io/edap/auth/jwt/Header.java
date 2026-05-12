package io.edap.auth.jwt;

public interface Header {
    String getAlgorithm();
    String getType();
    String getKeyId();
}
