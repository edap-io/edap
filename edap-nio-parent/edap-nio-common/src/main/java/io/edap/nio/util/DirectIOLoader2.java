package io.edap.nio.util;

public class DirectIOLoader2 extends ClassLoader {

    public DirectIOLoader2(ClassLoader parent) {
        super(parent);
    }

    public Class define(String className, byte[] bs, int offset, int len) {
        return super.defineClass(className, bs, offset, len);
    }
}
