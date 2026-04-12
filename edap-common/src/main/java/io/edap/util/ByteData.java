package io.edap.util;

/**
 * 经常使用的Byte数组的数据，如果数据固定在某个范围内则缓存数据到该结构，利用Length和位置来重复使用该结构，减少
 * byte数组的生成
 */
public class ByteData {
    private byte[] bytes;
    private int offset;
    private int length;

    public ByteData() {
        this(4096);
    }

    public ByteData(int length) {
        this.bytes = new byte[length];
        this.offset = 0;
        this.length = 0;
    }

    public byte[] getBytes() {
        return bytes;
    }

    public void setBytes(byte[] bytes) {
        this.bytes = bytes;
    }

    public int getOffset() {
        return offset;
    }

    public void setOffset(int offset) {
        this.offset = offset;
    }

    public int getLength() {
        return length;
    }

    public void setLength(int length) {
        this.length = length;
    }
}
