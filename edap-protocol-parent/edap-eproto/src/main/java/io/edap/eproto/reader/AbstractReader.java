package io.edap.eproto.reader;

import io.edap.eproto.EprotoReader;
import io.edap.protobuf.ProtoException;

import static io.edap.eproto.EprotoReader.decodeZigZag32;

public abstract class AbstractReader implements EprotoReader {

    /**
     * 获取boolean，如果为zigzap的1则为true其他均为false
     * @return
     * @throws ProtoException
     */
    @Override
    public boolean readBool() throws ProtoException {
        return getByte() == 2;
    }

    @Override
    public float readFloat() throws ProtoException {
        return Float.intBitsToFloat(readFixed32());
    }

    @Override
    public int readInt32() throws ProtoException {
        return readRawVarint32();
    }

    @Override
    public int readUInt32() throws ProtoException {
        return readRawVarint32();
    }

    @Override
    public int readSInt32() throws ProtoException {
        return decodeZigZag32(readRawVarint32());
    }

    @Override
    public int readFixed32() throws ProtoException {
        return readRawLittleEndian32();
    }

    @Override
    public int readSFixed32() throws ProtoException {
        return readRawLittleEndian32();
    }

    abstract int readRawVarint32() throws ProtoException;

    abstract int readRawLittleEndian32() throws ProtoException;
}
