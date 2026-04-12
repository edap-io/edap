package io.edap.nio;

import java.io.FileDescriptor;
import java.io.IOException;

public interface DirectIO {

    int read0(FileDescriptor fd, long address, int len) throws IOException;

    int write0(FileDescriptor fd, long address, int len) throws IOException;
}