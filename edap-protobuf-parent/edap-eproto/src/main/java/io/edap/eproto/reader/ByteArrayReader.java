package io.edap.eproto.reader;

import io.edap.eproto.EprotoDecoder;
import io.edap.eproto.EprotoReader;
import io.edap.protobuf.ProtoException;
import io.edap.protobuf.wire.Field;

import java.util.ArrayList;
import java.util.List;

import static java.util.Collections.EMPTY_LIST;

import static io.edap.eproto.EprotoReader.decodeZigZag32;

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
        }
        switch (type) {
            case INT32:
            case UINT32:
                return readUInt32List(size);
            case SINT32:
                return readSInt32List(size);
            case FIXED32:
            case SFIXED32:
                return readFixed32List(size);
            default:
        }
        return null;
    }

    private List<Integer> readFixed32List(int size) throws ProtoException {
        List<Integer> list = new ArrayList<>(size);
        byte[] _buf   = buf;
        int    _pos   = pos;
        if (limit - _pos < (size << 2) ) {
            throw ProtoException.truncatedMessage();
        }
        for (int i=0;i<size;i++) {
            list.add(
                     (((_buf[_pos++] & 0xFF)      )
                    | ((_buf[_pos++] & 0xFF) << 8 )
                    | ((_buf[_pos++] & 0xFF) << 16)
                    | ((_buf[_pos++] & 0xFF) << 24)));
        }
        pos = _pos;
        return list;
    }

    private List<Integer> readSInt32List(int size) throws ProtoException {
        List<Integer> list = new ArrayList<>(size);
        byte[] _buf   = buf;
        int    _pos   = pos;
        int    _limit = limit;
        int    rsize  = 0;
        while (_pos < limit) {
            int x;
            if ((x = _buf[_pos++]) >= 0) {
                list.add(decodeZigZag32(x));
                rsize++;
                if (rsize == size) {
                    return list;
                } else {
                    continue;
                }
            } else if (_limit - _pos < 4) {
                break;
            } else if ((x ^= (_buf[_pos++] << 7)) < 0) {
                x ^= (~0 << 7);
            } else if ((x ^= (_buf[_pos++] << 14)) < 0) {
                x ^= (~0 << 7) ^ (~0 << 14);
            } else if ((x ^= (_buf[_pos++] << 21)) < 0) {
                x ^= (~0 << 7) ^ (~0 << 14) ^ (~0 << 21);
            } else {
                int y = _buf[_pos++];
                x ^= y << 28;
                x ^= (~0 << 7) ^ (~0 << 14) ^ (~0 << 21) ^ (~0 << 28);
                if (y < 0) {
                    throw ProtoException.malformedVarint();
                }
            }
            list.add(decodeZigZag32(x));
            rsize++;
            if (rsize == size) {
                return list;
            }
        }
        _pos--;
        int x = 0;
        int shift  = 0;
        for (; shift < 32 && _pos < _limit; shift += 7) {
            final byte b = _buf[_pos++];
            x |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                list.add(decodeZigZag32(x));
                rsize++;
                if (rsize == size) {
                    return list;
                }
                shift = 0;
                x = 0;
            }
        }
        if (rsize < size) {
            throw ProtoException.malformedVarint();
        }
        return list;
    }

    private List<Integer> readUInt32List(int size) throws ProtoException {
        List<Integer> list = new ArrayList<>(size);
        byte[] _buf   = buf;
        int    _pos   = pos;
        int    _limit = limit;
        int    rsize  = 0;
        while (_pos < limit) {
            int x;
            if ((x = _buf[_pos++]) >= 0) {
                list.add(x);
                rsize++;
                if (rsize == size) {
                    return list;
                } else {
                    continue;
                }
            } else if (_limit - _pos < 4) {
                break;
            } else if ((x ^= (_buf[_pos++] << 7)) < 0) {
                x ^= (~0 << 7);
            } else if ((x ^= (_buf[_pos++] << 14)) < 0) {
                x ^= (~0 << 7) ^ (~0 << 14);
            } else if ((x ^= (_buf[_pos++] << 21)) < 0) {
                x ^= (~0 << 7) ^ (~0 << 14) ^ (~0 << 21);
            } else {
                int y = _buf[_pos++];
                x ^= y << 28;
                x ^= (~0 << 7) ^ (~0 << 14) ^ (~0 << 21) ^ (~0 << 28);
                if (y < 0) {
                    throw ProtoException.malformedVarint();
                }
            }
            list.add(x);
            rsize++;
            if (rsize == size) {
                return list;
            }
        }
        _pos--;
        int result = 0;
        int shift  = 0;
        for (; shift < 32 && _pos < _limit; shift += 7) {
            final byte b = _buf[_pos++];
            result |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                list.add(result);
                rsize++;
                if (rsize == size) {
                    return list;
                }
                shift = 0;
                result = 0;
            }
        }
        if (rsize < size) {
            throw ProtoException.malformedVarint();
        }
        return list;
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
