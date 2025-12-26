package io.edap.nio.nativeimpl;

import java.io.FileDescriptor;
import java.io.IOException;

public class FastNetIO {



    public static native int read0(FileDescriptor fd, long address, int len)
            throws IOException;

    public static native int write0(FileDescriptor fd, long address, int len)
            throws IOException;

    static native void initIDs();
}
