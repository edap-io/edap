package io.edap.buffer;

import io.edap.util.ByteData;

import java.io.IOException;
import java.io.OutputStream;

public class BytesBuf extends OutputStream {

    private byte[] buf;
    private int pos;
    private int wpos;

    public BytesBuf(byte[] buf) {
        this.buf = buf;
        this.pos = 0;
    }

    public byte[] getBuf() {
        return buf;
    }

    public void setBuf(byte[] buf) {
        this.buf = buf;
    }

    public int getPos() {
        return pos;
    }

    public void setPos(int pos) {
        this.pos = pos;
    }

    public void write(byte[] bs) throws IOException {
        write(bs, 0, bs.length);
    }

    public void write(ByteData byteData) throws IOException {
        int len = byteData.getLength();
        write(byteData.getBytes(), 0, len);
    }

    @Override
    public void write(byte b[], int off, int len) throws IOException {
        if (buf.length - pos < len) {
            int newLen = pos + len;
            int doule = buf.length << 1;
            if (newLen < doule) {
                newLen = doule;
            }
            byte[] tmp = new byte[newLen];
            System.arraycopy(buf, 0, tmp, 0, pos);
            System.arraycopy(b, off, tmp, pos, len);
            pos += len;
            this.buf = tmp;
        } else {
            System.arraycopy(b, off, buf, pos, len);
            pos += len;
        }
    }

    @Override
    public void write(int b) throws IOException {

    }

    public int getWpos() {
        return wpos;
    }

    public void setWpos(int wpos) {
        this.wpos = wpos;
    }
}
