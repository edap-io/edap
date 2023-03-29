package io.edap.log.io;

import io.edap.log.LogOutputStream;

import java.io.IOException;
import java.io.OutputStream;

public class BaseLogOutputStream extends OutputStream implements LogOutputStream {

    @Override
    public void write(int b) throws IOException {

    }

    @Override
    public void writeLog(byte[] data, int offset, int length) {

    }
}
