package io.edap.eproto.reader;

import io.edap.eproto.EprotoDecoder;
import io.edap.protobuf.ProtoException;
import io.edap.protobuf.wire.Field;

import java.util.ArrayList;
import java.util.List;

import static java.util.Collections.EMPTY_LIST;

public class ByteArrayReader extends AbstractReader {

    protected final byte[] buf;
    protected       int    pos;
    protected       int    limit;

    public ByteArrayReader(byte [] buf) {
        this.buf   = buf;
        this.pos   = 0;
        this.limit = buf.length;
    }

    public ByteArrayReader(byte [] buf, int offset, int len) {
        this.buf   = buf;
        this.limit = offset + len;
        this.pos   = offset;
    }

    @Override
    public byte getByte() {
        return buf[pos++];
    }

    @Override
    public List<Boolean> readPackedBool() throws ProtoException {
        int size = readSInt32();
        if (size == -1) {
            return null;
        } else if (size == 0) {
            return EMPTY_LIST;
        } else {
            List<Boolean> list = new ArrayList<>(size);
            byte[] _bs = buf;
            int _pos = pos;
            for (int i = 0; i < size; i++) {
                list.add(_bs[_pos++] == 2 ? true : false);
            }
            pos = _pos;
            return list;
        }
    }

    @Override
    public boolean[] readPackedBoolValues() throws ProtoException {
        int size = readSInt32();
        if (size == -1) {
            return null;
        } else if (size == 0) {
            return new boolean[0];
        } else {
            boolean[] vs = new boolean[size];
            byte[] _bs = buf;
            int _pos = pos;
            for (int i = 0; i < size; i++) {
                vs[i] = _bs[_pos++] == 2 ? true : false;
            }
            pos = _pos;
            return vs;
        }
    }

    @Override
    public Boolean[] readPackedBools() throws ProtoException {
        int size = readSInt32();
        if (size == -1) {
            return null;
        } else if (size == 0) {
            return new Boolean[0];
        } else {
            Boolean[] vs = new Boolean[size];
            byte[] _bs = buf;
            int _pos = pos;
            for (int i = 0; i < size; i++) {
                vs[i] = _bs[_pos++] == 2 ? true : false;
            }
            pos = _pos;
            return vs;
        }
    }

    @Override
    public List<Integer> readPackedInt32(Field.Type type) throws ProtoException {
        int size = readSInt32();
        if (size == -1) {
            return null;
        } else if (size == 0) {
            return EMPTY_LIST;
        } else {

        }
    }

    @Override
    public Integer[] readPackedInt32Array(Field.Type type) throws ProtoException {
        return new Integer[0];
    }

    @Override
    public int[] readPackedInt32ArrayValue(Field.Type type) throws ProtoException {
        return new int[0];
    }

    @Override
    public List<Float> readPackedFloat() throws ProtoException {
        return null;
    }

    @Override
    public Float[] readPackedFloatArray() throws ProtoException {
        return new Float[0];
    }

    @Override
    public float[] readPackedFloatArrayValue() throws ProtoException {
        return new float[0];
    }

    @Override
    public List<Long> readPackedInt64(Field.Type type) throws ProtoException {
        return null;
    }

    @Override
    public Long[] readPackedInt64Array(Field.Type type) throws ProtoException {
        return new Long[0];
    }

    @Override
    public long[] readPackedInt64ArrayValue(Field.Type type) throws ProtoException {
        return new long[0];
    }

    @Override
    public List<Double> readPackedDouble() throws ProtoException {
        return null;
    }

    @Override
    public Double[] readPackedDoubleArray() throws ProtoException {
        return new Double[0];
    }

    @Override
    public double[] readPackedDoubleArrayValue() throws ProtoException {
        return new double[0];
    }

    @Override
    public long readInt64() throws ProtoException {
        return 0;
    }

    @Override
    public long readUInt64() throws ProtoException {
        return 0;
    }

    @Override
    public long readSInt64() throws ProtoException {
        return 0;
    }

    @Override
    public long readFixed64() throws ProtoException {
        return 0;
    }

    @Override
    public long readSFixed64() throws ProtoException {
        return 0;
    }

    @Override
    public double readDouble() throws ProtoException {
        return 0;
    }

    @Override
    public byte[] readBytes() throws ProtoException {
        return new byte[0];
    }

    @Override
    public String readString() throws ProtoException {
        return null;
    }

    @Override
    public String readString(int len) throws ProtoException {
        return null;
    }

    @Override
    public Object readObject() throws ProtoException {
        return null;
    }

    @Override
    public <T> T readMessage(EprotoDecoder<T> decoder) throws ProtoException {
        return null;
    }

    @Override
    public boolean skipField(int wireType) throws ProtoException {
        return false;
    }

    @Override
    public void reset() {

    }

    @Override
    int readRawVarint32() throws ProtoException {
        return 0;
    }

    @Override
    int readRawLittleEndian32() throws ProtoException {
        return 0;
    }
}
