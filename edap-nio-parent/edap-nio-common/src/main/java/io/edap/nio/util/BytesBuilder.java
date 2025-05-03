package io.edap.nio.util;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

public class BytesBuilder {

    private byte[] data;

    private int pos;

    public BytesBuilder() {
        data = new byte[1024];
    }

    public BytesBuilder(int cap) {
        data = new byte[cap];
    }

    public byte get(int pos) {
        return data[pos];
    }

    public int length() {
        return pos;
    }

    public byte[] getData() {
        return data;
    }

    public void setPos(int pos) {
        this.pos = pos;
    }

    public int getPos() {
        return this.pos;
    }

    public void write(byte[] bs) {
        write(bs, 0, bs.length);
    }

    public void write(byte b) {
        if (pos < data.length) {
            data[pos++] = b;
        } else {
            int newCap = data.length * 2;
            byte[] tmp = new byte[newCap];
            System.arraycopy(data, 0, tmp, 0, pos);
            tmp[pos++] = b;
            data = tmp;
        }
    }

    public void write(byte[] bs, int offset, int len) {
        if (pos + len < data.length) {
            System.arraycopy(bs, offset, data, pos, len);
        } else {
            int newCap = data.length * 2;
            if (newCap < pos + len) {
                newCap = pos + len + 1024;
            }
            byte[] tmp = new byte[newCap];
            System.arraycopy(data, 0, tmp, 0, pos);
            System.arraycopy(bs, offset, tmp, pos, len);
            data = tmp;
        }
        pos += len;
    }

    public void reset() {
        pos = 0;
    }

    public String toString() {
        return new String(data, 0, pos, StandardCharsets.UTF_8);
    }

    public String toString(Charset charset) {
        return new String(data, 0, pos, charset);
    }
}
