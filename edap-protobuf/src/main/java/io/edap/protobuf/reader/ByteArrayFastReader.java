package io.edap.protobuf.reader;

import io.edap.protobuf.ProtoBufDecoder;
import io.edap.protobuf.ProtoBufException;
import io.edap.protobuf.wire.Field;

import java.util.ArrayList;
import java.util.List;

public class ByteArrayFastReader extends ByteArrayReader {
    public ByteArrayFastReader(byte[] buf) {
        super(buf);
    }

    public ByteArrayFastReader(byte [] buf, int offset, int len) {
        super(buf, offset, len);
    }

    @Override
    public Long[] readPackedInt64Array(Field.Type type) throws ProtoBufException {
        Long[] tmp = LOCAL_TMP_LONG_ARRAY.get();
        int len = readRawVarint32();
        int i = 0;
        switch (type) {
            case INT64:
            case UINT64:
                for (i=0;i<len;i++) {
                    tmp[i] = readRawVarint64();
                    expandLocalLongArray(tmp, i);
                }
                break;
            case SINT64:
                for (i=0;i<len;i++) {
                    tmp[i] = readSInt64();
                    expandLocalLongArray(tmp, i);
                }
                break;
            case FIXED64:
            case SFIXED64:
                int old = pos;
                while (pos - old < len) {
                    tmp[i++] = readSFixed64();
                    expandLocalLongArray(tmp, i);
                }
                break;
            default:
                throw ProtoBufException.malformedVarint();
        }

        Long [] res = new Long[i];
        System.arraycopy(tmp, 0, res, 0, i);
        return res;
    }

    @Override
    public List<Long> readPackedInt64(Field.Type type)
            throws ProtoBufException {
        List<Long> list = new ArrayList<>();
        int len = readRawVarint32();
        switch (type) {
            case INT64:
            case UINT64:
                for (int i=0;i<len;i++) {
                    list.add(readRawVarint64());
                }
                break;
            case SINT64:
                for (int i=0;i<len;i++) {
                    list.add(readSInt64());
                }
                break;
            case FIXED64:
            case SFIXED64:
                int old = pos;
                while (pos - old < len) {
                    list.add(readFixed64());
                }
                break;
            default:
                throw ProtoBufException.malformedVarint();
        }
        return list;
    }

    @Override
    public int[] readPackedInt32ArrayValue(Field.Type type) throws ProtoBufException {
        int[] tmp = LOCAL_TMP_INT_ARRAY.get();
        int len = readRawVarint32();
        expandLocalIntArray(tmp, len);
        int i = 0;
        switch (type) {
            case INT32:
            case UINT32:
                for (i=0;i<len;i++) {
                    tmp[i] = readRawVarint32();
                }
                break;
            case SINT32:
                for (i=0;i<len;i++) {
                    tmp[i] = readSInt32();
                }
                break;
            case FIXED32:
            case SFIXED32:
                int old = pos;
                while (pos - old < len) {
                    tmp[i++] = readRawLittleEndian32();
                }
                break;
            default:
                throw ProtoBufException.malformedVarint();
        }

        int [] res = new int[i];
        System.arraycopy(tmp, 0, res, 0, i);
        return res;
    }

    @Override
    public Integer[] readPackedInt32Array(Field.Type type) throws ProtoBufException {
        Integer[] tmp = LOCAL_TMP_INTEGER_ARRAY.get();
        int len = readRawVarint32();
        expandLocalIntegerArray(tmp, len);
        int old = pos;
        int i = 0;
        switch (type) {
            case INT32:
            case UINT32:
                for (i=0;i<len;i++) {
                    tmp[i] = readRawVarint32();
                }
                break;
            case SINT32:
                for (i=0;i<len;i++) {
                    tmp[i] = readSInt32();
                }
                break;
            case FIXED32:
            case SFIXED32:
                while (pos - old < len) {
                    tmp[i++] = readRawLittleEndian32();
                }
                break;
            default:
                throw ProtoBufException.malformedVarint();
        }
        Integer [] res = new Integer[i];
        System.arraycopy(tmp, 0, res, 0, i);
        return res;
    }

    @Override
    public List<Integer> readPackedInt32(Field.Type type) throws ProtoBufException {
        List<Integer> list = new ArrayList<>();
        int len = readRawVarint32();
        switch (type) {
            case INT32:
            case UINT32:
                for (int i=0;i<len;i++) {
                    list.add(readRawVarint32());
                }
                break;
            case SINT32:
                for (int i=0;i<len;i++) {
                    list.add(readSInt32());
                }
                break;
            case FIXED32:
            case SFIXED32:
                for (int i=0;i<len;i++) {
                    list.add(readRawLittleEndian32());
                }
                break;
            default:
                throw ProtoBufException.malformedVarint();
        }
        return list;
    }

    public <T extends Object> T readMessage(ProtoBufDecoder<T> decoder, int endTag)
            throws ProtoBufException {
        return decoder.decode(this, endTag);
    }
}
