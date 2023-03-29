package io.edap.log.io;

import io.edap.log.LogOutputStream;

import java.io.IOException;
import java.io.OutputStream;

public class BaseLogOutputStream extends OutputStream implements LogOutputStream {

    private OutputStream outputStream;

    public BaseLogOutputStream(OutputStream outputStream) {
        this.outputStream = outputStream;
    }

    @Override
    public void write(int b) throws IOException {
        outputStream.write(b);
    }

    @Override
    public void writeLog(byte[] data, int offset, int length) throws IOException {
        outputStream.write(data, offset, length);
    }

    public void close() throws IOException {
        super.close();
    }
}
