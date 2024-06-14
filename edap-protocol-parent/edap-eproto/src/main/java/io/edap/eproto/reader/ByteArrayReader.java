package io.edap.eproto.reader;

import io.edap.eproto.EprotoDecoder;
import io.edap.eproto.EprotoReader;
import io.edap.protobuf.ProtoException;
import io.edap.protobuf.wire.Field;
import io.edap.util.StringUtil;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static io.edap.eproto.writer.AbstractWriter.*;
import static io.edap.protobuf.wire.WireFormat.FIXED_32_SIZE;
import static io.edap.util.Constants.EMPTY_STRING;
import static io.edap.util.StringUtil.IS_BYTE_ARRAY;
import static io.edap.util.StringUtil.fastInstance;
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
                    pos = _pos;
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
                pos = _pos;
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
                    pos = _pos;
                    return list;
                }
                shift = 0;
                result = 0;
            }
        }
        if (rsize < size) {
            throw ProtoException.malformedVarint();
        }
        pos = _pos;
        return list;
    }

    @Override
    public Integer[] readPackedInt32Array(Field.Type type) throws ProtoException {
        int size = readSInt32();
        if (size == -1) {
            return null;
        } else if (size == 0) {
            return new Integer[0];
        }
        switch (type) {
            case INT32:
            case UINT32:
                return readUInt32ObjArray(size);
            case SINT32:
                return readSInt32ObjArray(size);
            case FIXED32:
            case SFIXED32:
                return readFixed32ObjArray(size);
            default:
        }
        return null;
    }

    private Integer[] readFixed32ObjArray(int size) throws ProtoException {
        Integer[] array = new Integer[size];
        byte[] _buf   = buf;
        int    _pos   = pos;
        if (limit - _pos < (size << 2) ) {
            throw ProtoException.truncatedMessage();
        }
        for (int i=0;i<size;i++) {
            array[i] =
                    (((_buf[_pos++] & 0xFF)      )
                            | ((_buf[_pos++] & 0xFF) << 8 )
                            | ((_buf[_pos++] & 0xFF) << 16)
                            | ((_buf[_pos++] & 0xFF) << 24));
        }
        pos = _pos;
        return array;
    }

    private Integer[] readSInt32ObjArray(int size) throws ProtoException {
        Integer[] array = new Integer[size];
        byte[] _buf   = buf;
        int    _pos   = pos;
        int    _limit = limit;
        int    rsize  = 0;
        while (_pos < limit) {
            int x;
            if ((x = _buf[_pos++]) >= 0) {
                array[rsize++] = decodeZigZag32(x);
                if (rsize == size) {
                    pos = _pos;
                    return array;
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
            array[rsize++] = decodeZigZag32(x);
            if (rsize == size) {
                pos = _pos;
                return array;
            }
        }
        _pos--;
        int result = 0;
        int shift  = 0;
        for (; shift < 32 && _pos < _limit; shift += 7) {
            final byte b = _buf[_pos++];
            result |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                array[rsize++] = decodeZigZag32(result);
                if (rsize == size) {
                    pos = _pos;
                    return array;
                }
                shift = 0;
                result = 0;
            }
        }
        if (rsize < size) {
            throw ProtoException.malformedVarint();
        }
        pos = _pos;
        return array;
    }

    private Integer[] readUInt32ObjArray(int size) throws ProtoException {
        Integer[] array = new Integer[size];
        byte[] _buf   = buf;
        int    _pos   = pos;
        int    _limit = limit;
        int    rsize  = 0;
        while (_pos < limit) {
            int x;
            if ((x = _buf[_pos++]) >= 0) {
                array[rsize++] = x;
                if (rsize == size) {
                    pos = _pos;
                    return array;
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
            array[rsize++] = x;
            if (rsize == size) {
                pos = _pos;
                return array;
            }
        }
        _pos--;
        int result = 0;
        int shift  = 0;
        for (; shift < 32 && _pos < _limit; shift += 7) {
            final byte b = _buf[_pos++];
            result |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                array[rsize++] = result;
                if (rsize == size) {
                    pos = _pos;
                    return array;
                }
                shift = 0;
                result = 0;
            }
        }
        if (rsize < size) {
            throw ProtoException.malformedVarint();
        }
        pos = _pos;
        return array;
    }

    @Override
    public int[] readPackedInt32ArrayValue(Field.Type type) throws ProtoException {
        int size = readSInt32();
        if (size == -1) {
            return null;
        } else if (size == 0) {
            return new int[0];
        }
        switch (type) {
            case INT32:
            case UINT32:
                return readUInt32Array(size);
            case SINT32:
                return readSInt32Array(size);
            case FIXED32:
            case SFIXED32:
                return readFixed32Array(size);
            default:
        }
        return null;
    }

    private int[] readFixed32Array(int size) throws ProtoException {
        int[] array = new int[size];
        byte[] _buf   = buf;
        int    _pos   = pos;
        if (limit - _pos < (size << 2) ) {
            throw ProtoException.truncatedMessage();
        }
        for (int i=0;i<size;i++) {
            array[i] =
                    (((_buf[_pos++] & 0xFF)      )
                            | ((_buf[_pos++] & 0xFF) << 8 )
                            | ((_buf[_pos++] & 0xFF) << 16)
                            | ((_buf[_pos++] & 0xFF) << 24));
        }
        pos = _pos;
        return array;
    }

    private int[] readSInt32Array(int size) throws ProtoException {
        int[] array = new int[size];
        byte[] _buf   = buf;
        int    _pos   = pos;
        int    _limit = limit;
        int    rsize  = 0;
        while (_pos < limit) {
            int x;
            if ((x = _buf[_pos++]) >= 0) {
                array[rsize++] = decodeZigZag32(x);
                if (rsize == size) {
                    pos = _pos;
                    return array;
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
            array[rsize++] = decodeZigZag32(x);
            if (rsize == size) {
                pos = _pos;
                return array;
            }
        }
        _pos--;
        int result = 0;
        int shift  = 0;
        for (; shift < 32 && _pos < _limit; shift += 7) {
            final byte b = _buf[_pos++];
            result |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                array[rsize++] = decodeZigZag32(result);
                if (rsize == size) {
                    pos = _pos;
                    return array;
                }
                shift = 0;
                result = 0;
            }
        }
        if (rsize < size) {
            throw ProtoException.malformedVarint();
        }
        pos = _pos;
        return array;
    }

    private int[] readUInt32Array(int size) throws ProtoException {
        int[] array = new int[size];
        byte[] _buf   = buf;
        int    _pos   = pos;
        int    _limit = limit;
        int    rsize  = 0;
        while (_pos < limit) {
            int x;
            if ((x = _buf[_pos++]) >= 0) {
                array[rsize++] = x;
                if (rsize == size) {
                    pos = _pos;
                    return array;
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
            array[rsize++] = x;
            if (rsize == size) {
                pos = _pos;
                return array;
            }
        }
        _pos--;
        int result = 0;
        int shift  = 0;
        for (; shift < 32 && _pos < _limit; shift += 7) {
            final byte b = _buf[_pos++];
            result |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                array[rsize++] = result;
                if (rsize == size) {
                    pos = _pos;
                    return array;
                }
                shift = 0;
                result = 0;
            }
        }
        if (rsize < size) {
            throw ProtoException.malformedVarint();
        }
        pos = _pos;
        return array;
    }

    @Override
    public List<Float> readPackedFloat() throws ProtoException {
        int size = readSInt32();
        if (size == -1) {
            return null;
        } else if (size == 0) {
            return EMPTY_LIST;
        }

        if (limit - pos < (size << 2)) {
            throw ProtoException.sizeLimitExceeded();
        }
        List<Float> vs = new ArrayList<>(size);
        int _pos = pos;
        byte[] _bs = buf;
        for (int i=0;i<size;i++) {
            int v =  (((_bs[_pos++] & 0xFF)      )
                    | ((_bs[_pos++] & 0xFF) << 8 )
                    | ((_bs[_pos++] & 0xFF) << 16)
                    | ((_bs[_pos++] & 0xFF) << 24));
            vs.add(Float.intBitsToFloat(v));
        }
        pos = _pos;
        return vs;
    }

    @Override
    public Float[] readPackedFloatArray() throws ProtoException {
        int size = readSInt32();
        if (size == -1) {
            return null;
        } else if (size == 0) {
            return new Float[0];
        }

        if (limit - pos < (size << 2)) {
            throw ProtoException.sizeLimitExceeded();
        }
        Float[] vs = new Float[size];
        int _pos = pos;
        byte[] _bs = buf;
        for (int i=0;i<size;i++) {
            int v =  (((_bs[_pos++] & 0xFF)      )
                    | ((_bs[_pos++] & 0xFF) << 8 )
                    | ((_bs[_pos++] & 0xFF) << 16)
                    | ((_bs[_pos++] & 0xFF) << 24));
            vs[i] = Float.intBitsToFloat(v);
        }
        pos = _pos;
        return vs;
    }

    @Override
    public float[] readPackedFloatArrayValue() throws ProtoException {
        int size = readSInt32();
        if (size == -1) {
            return null;
        } else if (size == 0) {
            return new float[0];
        }

        if (limit - pos < (size << 2)) {
            throw ProtoException.sizeLimitExceeded();
        }
        float[] vs = new float[size];
        int _pos = pos;
        byte[] _bs = buf;
        for (int i=0;i<size;i++) {
            int v =  (((_bs[_pos++] & 0xFF)      )
                    | ((_bs[_pos++] & 0xFF) << 8 )
                    | ((_bs[_pos++] & 0xFF) << 16)
                    | ((_bs[_pos++] & 0xFF) << 24));
            vs[i] = Float.intBitsToFloat(v);
        }
        pos = _pos;
        return vs;
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
        int size = readSInt32();
        if (size == -1) {
            return null;
        } else if (size == 0) {
            return EMPTY_LIST;
        }

        if (limit - pos < (size << 3)) {
            throw ProtoException.sizeLimitExceeded();
        }
        List<Double> vs = new ArrayList<>(size);
        int _pos = pos;
        byte[] _bs = buf;
        for (int i=0;i<size;i++) {
            long v = (((_bs[_pos++] & 0xFFL)      )
                    | ((_bs[_pos++] & 0xFFL) << 8 )
                    | ((_bs[_pos++] & 0xFFL) << 16)
                    | ((_bs[_pos++] & 0xFFL) << 24)
                    | ((_bs[_pos++] & 0xFFL) << 32 )
                    | ((_bs[_pos++] & 0xFFL) << 40)
                    | ((_bs[_pos++] & 0xFFL) << 48)
                    | ((_bs[_pos++] & 0xFFL) << 56));
            vs.add(Double.longBitsToDouble(v));
        }
        pos = _pos;
        return vs;
    }

    @Override
    public Double[] readPackedDoubleArray() throws ProtoException {
        int size = readSInt32();
        if (size == -1) {
            return null;
        } else if (size == 0) {
            return new Double[0];
        }

        if (limit - pos < (size << 3)) {
            throw ProtoException.sizeLimitExceeded();
        }
        Double[] vs = new Double[size];
        int _pos = pos;
        byte[] _bs = buf;
        for (int i=0;i<size;i++) {
            long v = (((_bs[_pos++] & 0xFFL)      )
                    | ((_bs[_pos++] & 0xFFL) << 8 )
                    | ((_bs[_pos++] & 0xFFL) << 16)
                    | ((_bs[_pos++] & 0xFFL) << 24 )
                    | ((_bs[_pos++] & 0xFFL) << 32)
                    | ((_bs[_pos++] & 0xFFL) << 40 )
                    | ((_bs[_pos++] & 0xFFL) << 48)
                    | ((_bs[_pos++] & 0xFFL) << 56));
            vs[i] = Double.longBitsToDouble(v);
        }
        pos = _pos;
        return vs;
    }

    @Override
    public double[] readPackedDoubleArrayValue() throws ProtoException {
        int size = readSInt32();
        if (size == -1) {
            return null;
        } else if (size == 0) {
            return new double[0];
        }

        if (limit - pos < (size << 3)) {
            throw ProtoException.sizeLimitExceeded();
        }
        double[] vs = new double[size];
        int _pos = pos;
        byte[] _bs = buf;
        for (int i=0;i<size;i++) {
            long v = (((_bs[_pos++] & 0xFFL)      )
                    | ((_bs[_pos++] & 0xFFL) << 8 )
                    | ((_bs[_pos++] & 0xFFL) << 16)
                    | ((_bs[_pos++] & 0xFFL) << 24)
                    | ((_bs[_pos++] & 0xFFL) << 32)
                    | ((_bs[_pos++] & 0xFFL) << 40)
                    | ((_bs[_pos++] & 0xFFL) << 48)
                    | ((_bs[_pos++] & 0xFFL) << 56));
            vs[i] = Double.longBitsToDouble(v);
        }
        pos = _pos;
        return vs;
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
        int _pos = pos;
        if (limit - _pos < 8) {
            throw ProtoException.malformedVarint();
        }
        byte[] _bs = buf;
        long v =     (((_bs[_pos++] & 0xFFL)      )
                    | ((_bs[_pos++] & 0xFFL) << 8 )
                    | ((_bs[_pos++] & 0xFFL) << 16)
                    | ((_bs[_pos++] & 0xFFL) << 24)
                    | ((_bs[_pos++] & 0xFFL) << 32)
                    | ((_bs[_pos++] & 0xFFL) << 40)
                    | ((_bs[_pos++] & 0xFFL) << 48)
                    | ((_bs[_pos++] & 0xFFL) << 56));
        pos = _pos;
        return v;
    }

    @Override
    public long readSFixed64() throws ProtoException {
        int _pos = pos;
        if (limit - _pos < 8) {
            throw ProtoException.malformedVarint();
        }
        byte[] _bs = buf;
        long v = (((_bs[_pos++] & 0xFFL)      )
                | ((_bs[_pos++] & 0xFFL) << 8 )
                | ((_bs[_pos++] & 0xFFL) << 16)
                | ((_bs[_pos++] & 0xFFL) << 24)
                | ((_bs[_pos++] & 0xFFL) << 32)
                | ((_bs[_pos++] & 0xFFL) << 40)
                | ((_bs[_pos++] & 0xFFL) << 48)
                | ((_bs[_pos++] & 0xFFL) << 56));
        pos = _pos;
        return EprotoReader.decodeZigZag64(v);
    }

    @Override
    public double readDouble() throws ProtoException {
        int _pos = pos;
        if (limit - _pos < 8) {
            throw ProtoException.malformedVarint();
        }
        byte[] _bs = buf;
        long v = (((_bs[_pos++] & 0xFFL)      )
                | ((_bs[_pos++] & 0xFFL) << 8 )
                | ((_bs[_pos++] & 0xFFL) << 16)
                | ((_bs[_pos++] & 0xFFL) << 24)
                | ((_bs[_pos++] & 0xFFL) << 32)
                | ((_bs[_pos++] & 0xFFL) << 40)
                | ((_bs[_pos++] & 0xFFL) << 48)
                | ((_bs[_pos++] & 0xFFL) << 56));
        pos = _pos;

        return Double.longBitsToDouble(v);
    }

    @Override
    public byte[] readBytes() throws ProtoException {
        int size = readSInt32();
        if (size == -1) {
            return null;
        } else if (size == 0) {
            return new byte[0];
        }
        if (limit - pos < size) {
            throw ProtoException.malformedVarint();
        }
        byte[] data = new byte[size];
        System.arraycopy(buf, pos, data, 0, size);
        pos += size;
        return data;
    }

    @Override
    public String readString() throws ProtoException {
        byte b = buf[pos++];
        if (b == ZIGZAG32_NEGATIVE_ONE) {
            return null;
        } else if (b == ZIGZAG32_ZERO) {
            return EMPTY_STRING;
        } else if (b == ZIGZAG32_ONE) {
            int len = readInt32();
            byte[] data = new byte[len];
            System.arraycopy(buf, pos, data, 0, len);
            try {
                return fastInstance(data, (byte)0);
            } catch (InstantiationException e) {
                throw new ProtoException(e);
            }
        } else if (b == ZIGZAG32_TWO) {
            int len = readInt32();
            byte[] data = new byte[len];
            System.arraycopy(buf, pos, data, 0, len);
            if (IS_BYTE_ARRAY) {
                try {
                    return fastInstance(data, (byte)1);
                } catch (InstantiationException e) {
                    throw new ProtoException(e);
                }
            }
        }
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
        fastpath: {
            int tmpPos = pos;
            if (tmpPos == limit) {
                break fastpath;
            }
            int x;
            if ((x = buf[tmpPos++]) >= 0) {
                pos = tmpPos;
                return x;
            } else if (limit - tmpPos < 9) {
                break fastpath;
            } else if ((x ^= (buf[tmpPos++] << 7)) < 0) {
                x ^= (~0 << 7);
            } else if ((x ^= (buf[tmpPos++] << 14)) >= 0) {
                x ^= (~0 << 7) ^ (~0 << 14);
            } else if ((x ^= (buf[tmpPos++] << 21)) < 0) {
                x ^= (~0 << 7) ^ (~0 << 14) ^ (~0 << 21);
            } else {
                int y = buf[tmpPos++];
                x ^= y << 28;
                x ^= (~0 << 7) ^ (~0 << 14) ^ (~0 << 21) ^ (~0 << 28);
                if (y < 0
                        && buf[tmpPos++] < 0
                        && buf[tmpPos++] < 0
                        && buf[tmpPos++] < 0
                        && buf[tmpPos++] < 0
                        && buf[tmpPos++] < 0) {
                    break fastpath;
                }
            }
            pos = tmpPos;
            return x;
        }
        return (int) readRawVarint64SlowPath();
    }

    private long readRawVarint64SlowPath() throws ProtoException {
        long result = 0;
        for (int shift = 0; shift < 64 && pos < limit; shift += 7) {
            final byte b = buf[pos++];
            result |= (long) (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                return result;
            }
        }
        throw ProtoException.malformedVarint();
    }

    @Override
    int readRawLittleEndian32() throws ProtoException {
        if (limit - pos < FIXED_32_SIZE) {
            throw ProtoException.truncatedMessage();
        }
        return   (((buf[pos++] & 0xFF))
                | ((buf[pos++] & 0xFF) << 8)
                | ((buf[pos++] & 0xFF) << 16)
                | ((buf[pos++] & 0xFF) << 24));
    }
}
