package io.edap.protobuf.reader;

import io.edap.protobuf.ProtoBufDecoder;
import io.edap.protobuf.ProtoBufException;
import io.edap.protobuf.wire.Field;
import io.edap.protobuf.wire.WireType;

import java.util.ArrayList;
import java.util.List;
import java.util.Stack;

import static io.edap.protobuf.wire.WireFormat.*;
import static io.edap.protobuf.wire.WireType.END_GROUP;

public class ByteArrayFastReader extends ByteArrayReader {
    public ByteArrayFastReader(byte[] buf) {
        super(buf);
    }

    public ByteArrayFastReader(byte [] buf, int offset, int len) {
        super(buf, offset, len);
    }

    @Override
    public String readString() throws ProtoBufException {
        int len = readRawVarint32();
        if (len < 0) {
            return null;
        }
        char[] cs;
        if (len < 4096 && tmp == null) {
            cs = LOCAL_TMP_CHAR_ARRAY.get();
        } else {
            cs = new char[len];
        }
        int index = 0;
        int tmpPos = pos;
        while (index < len) {
            int b = buf[tmpPos++];
            if ((b & 0x80) == 0) {
                cs[index++] = (char)b;
            } else {
                byte b2 = buf[tmpPos++];
                if ((b & 0xE0) == 0xC0) {
                    cs[index++] = (char)(((b & 0x1F) << 6) + (b2 & 0x3F));
                } else {
                    byte b3 = buf[tmpPos++];
                    if ((b & 0xF0) == 0xE0) {
                        cs[index++] = (char)(((b & 0x0F) << 12)
                                + ((b2 & 0x3F) << 6) + (b3 & 0x3F));
                    } else {
                        byte b4 = buf[tmpPos++];
                        if ((b & 0xF8) == 0xF0) {
                            b = ((b & 0x07) << 18) + ((b2 & 0x3F) << 12)
                                    + ((b3 & 0x3F) << 6) + (b4 & 0x3F);
                        } else {
                            throw new RuntimeException();
                        }
                        if (b >= 0x10000) {
                            if (b >= 0x110000) {
                                throw new RuntimeException();
                            }
                            int sup = b - 0x10000;
                            cs[index++] = (char)((sup >>> 10) + 0xd800);
                            cs[index++] = (char)((sup & 0x3ff) + 0xdc00);
                        }
                    }
                }
            }
        }
        String s = new String(cs, 0, index);
        //String s = new String(buf, pos, len, StandardCharsets.UTF_8);
        pos = tmpPos;
        return s;
    }

//    @Override
//    public Long[] readPackedInt64Array(Field.Type type) throws ProtoBufException {
//        Long[] tmp = LOCAL_TMP_LONG_ARRAY.get();
//        int len = readRawVarint32();
//        int i = 0;
//        switch (type) {
//            case INT64:
//            case UINT64:
//                for (i=0;i<len;i++) {
//                    tmp[i] = readRawVarint64();
//                    expandLocalLongArray(tmp, i);
//                }
//                break;
//            case SINT64:
//                for (i=0;i<len;i++) {
//                    tmp[i] = readSInt64();
//                    expandLocalLongArray(tmp, i);
//                }
//                break;
//            case FIXED64:
//            case SFIXED64:
//                int old = pos;
//                while (pos - old < len) {
//                    tmp[i++] = readSFixed64();
//                    expandLocalLongArray(tmp, i);
//                }
//                break;
//            default:
//                throw ProtoBufException.malformedVarint();
//        }
//
//        Long [] res = new Long[i];
//        System.arraycopy(tmp, 0, res, 0, i);
//        return res;
//    }
//
//    @Override
//    public List<Long> readPackedInt64(Field.Type type)
//            throws ProtoBufException {
//        List<Long> list = new ArrayList<>();
//        int len = readRawVarint32();
//        switch (type) {
//            case INT64:
//            case UINT64:
//                for (int i=0;i<len;i++) {
//                    list.add(readRawVarint64());
//                }
//                break;
//            case SINT64:
//                for (int i=0;i<len;i++) {
//                    list.add(readSInt64());
//                }
//                break;
//            case FIXED64:
//            case SFIXED64:
//                int old = pos;
//                while (pos - old < len) {
//                    list.add(readFixed64());
//                }
//                break;
//            default:
//                throw ProtoBufException.malformedVarint();
//        }
//        return list;
//    }
//
//    @Override
//    public int[] readPackedInt32ArrayValue(Field.Type type) throws ProtoBufException {
//        int[] tmp = LOCAL_TMP_INT_ARRAY.get();
//        int len = readRawVarint32();
//        expandLocalIntArray(tmp, len);
//        int i = 0;
//        switch (type) {
//            case INT32:
//            case UINT32:
//                for (i=0;i<len;i++) {
//                    tmp[i] = readRawVarint32();
//                }
//                break;
//            case SINT32:
//                for (i=0;i<len;i++) {
//                    tmp[i] = readSInt32();
//                }
//                break;
//            case FIXED32:
//            case SFIXED32:
//                int old = pos;
//                while (pos - old < len) {
//                    tmp[i++] = readRawLittleEndian32();
//                }
//                break;
//            default:
//                throw ProtoBufException.malformedVarint();
//        }
//
//        int [] res = new int[i];
//        System.arraycopy(tmp, 0, res, 0, i);
//        return res;
//    }
//
//    @Override
//    public Integer[] readPackedInt32Array(Field.Type type) throws ProtoBufException {
//        Integer[] tmp = LOCAL_TMP_INTEGER_ARRAY.get();
//        int len = readRawVarint32();
//        expandLocalIntegerArray(tmp, len);
//        int old = pos;
//        int i = 0;
//        switch (type) {
//            case INT32:
//            case UINT32:
//                for (i=0;i<len;i++) {
//                    tmp[i] = readRawVarint32();
//                }
//                break;
//            case SINT32:
//                for (i=0;i<len;i++) {
//                    tmp[i] = readSInt32();
//                }
//                break;
//            case FIXED32:
//            case SFIXED32:
//                while (pos - old < len) {
//                    tmp[i++] = readRawLittleEndian32();
//                }
//                break;
//            default:
//                throw ProtoBufException.malformedVarint();
//        }
//        Integer [] res = new Integer[i];
//        System.arraycopy(tmp, 0, res, 0, i);
//        return res;
//    }
//
//    @Override
//    public List<Integer> readPackedInt32(Field.Type type) throws ProtoBufException {
//        List<Integer> list = new ArrayList<>();
//        int len = readRawVarint32();
//        switch (type) {
//            case INT32:
//            case UINT32:
//                for (int i=0;i<len;i++) {
//                    list.add(readRawVarint32());
//                }
//                break;
//            case SINT32:
//                for (int i=0;i<len;i++) {
//                    list.add(readSInt32());
//                }
//                break;
//            case FIXED32:
//            case SFIXED32:
//                int old = pos;
//                while (pos - old < len) {
//                    list.add(readRawLittleEndian32());
//                }
//                break;
//            default:
//                throw ProtoBufException.malformedVarint();
//        }
//        return list;
//    }

    public <T extends Object> T readMessage(ProtoBufDecoder<T> decoder, int endTag)
            throws ProtoBufException {
        return decoder.decode(this, endTag);
    }

    @Override
    boolean skipMessage(int tag) throws ProtoBufException {
        int tagNum = getTagFieldNumber(tag);
        int end = makeTag(tagNum, END_GROUP);
        int _pos = pos;
        Stack<Integer> msgStack = new Stack<>();
        while (_pos < limit) {
            int rawInt = readRawVarint32();
            if (rawInt == end && msgStack.empty()) {
                pos = _pos;
                return true;
            }
            int wireType = getTagWireType(rawInt);
            if (wireType == WireType.START_GROUP.getValue()) {
                msgStack.push(rawInt);
            } else if (wireType == END_GROUP.getValue()) {
                msgStack.pop();
            } else {
                switch (wireType) {
                    case 0:  //VARINT
                        skipRawVarint();
                        break;
                    case 5:  //FIXED32
                        skipRawBytes(FIXED_32_SIZE);
                        break;
                    case 1:  //FIXED64
                        skipRawBytes(FIXED_64_SIZE);
                        break;
                    case 2:  //LENGTH_DELIMITED
                        int len = readRawVarint32();
                        if (len >= 0) {
                            skipRawBytes(len);
                            break;
                        } else {
                            throw ProtoBufException.malformedVarint();
                        }
                    case 3:  //START_GROUP
                        skipMessage(tag);
                        break;
                    case 7:
                        skipString();
                        break;
                    default:
                        throw ProtoBufException.malformedVarint();

                }
                _pos = pos;
            }
            _pos++;
        }
        return true;
    }

    @Override
    boolean skipString() throws ProtoBufException {
        int len = readRawVarint32();
        readString(len);
        return true;
    }
}
