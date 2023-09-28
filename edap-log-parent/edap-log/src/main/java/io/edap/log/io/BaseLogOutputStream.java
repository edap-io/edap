package io.edap.log.io;

import io.edap.log.LogEvent;
import io.edap.log.RolloverLogWriter;

import java.io.IOException;
import java.io.OutputStream;

public class BaseLogOutputStream extends OutputStream implements RolloverLogWriter {

    private OutputStream outputStream;

    public BaseLogOutputStream(OutputStream outputStream) {
        this.outputStream = outputStream;
    }

    @Override
    public void write(int b) throws IOException {
        outputStream.write(b);
    }

    @Override
    public void rollover(LogEvent event) {

    }

    @Override
    public void writeLog(byte[] data, int offset, int length) throws IOException {
        outputStream.write(data, offset, length);
    }

    @Override
    public void close() throws IOException {
        outputStream.close();
    }

    @Override
    public void flush() throws IOException {
        outputStream.flush();
    }
}
