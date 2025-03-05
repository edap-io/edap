package io.edap.protobuf.reader;

import io.edap.protobuf.ProtoBufDecoder;
import io.edap.protobuf.ProtoException;
import io.edap.protobuf.wire.Field;

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
    public boolean isFastCodec() {
        return true;
    }

    @Override
    public String readString() throws ProtoException {
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
                            throw new RuntimeException("Not well UTF-8 String");
                        }
                        if (b >= 0x10000) {
                            if (b >= 0x110000) {
                                throw new RuntimeException("Not well UTF-8 String");
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

    @Override
    public List<Integer> readPackedInt32(Field.Type type) throws ProtoException {
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
                throw ProtoException.malformedVarint();
        }
        return list;
    }

    public <T extends Object> T readMessage(ProtoBufDecoder<T> decoder, int endTag)
            throws ProtoException {
        return decoder.decode(this, endTag);
    }

    @Override
    boolean skipMessage(int tag) throws ProtoException {
        int tagNum = getTagFieldNumber(tag);
        int end = makeTag(tagNum, END_GROUP);
        Stack<Integer> msgStack = new Stack<>();
        while (pos < limit) {
            int rawInt = readRawVarint32();
            if (rawInt == end && msgStack.empty()) {
                return true;
            }
            int wireType = getTagWireType(rawInt);
            switch (wireType) {
                case 0:  //VARINT
                    skipRawVarint();
                    break;
                case 1:  //FIXED64
                    skipRawBytes(FIXED_64_SIZE);
                    break;
                case 2:  //LENGTH_DELIMITED
                    int len = readRawVarint32();
                    skipRawBytes(len);
                    break;
                case 3:  //START_GROUP
                    msgStack.push(rawInt);
                    break;
                case 4:  //START_GROUP
                    msgStack.pop();
                    break;
                case 5:  //FIXED32
                    skipRawBytes(FIXED_32_SIZE);
                    break;
                case 6:
                    skipObject();
                    break;
                case 7:
                    skipString();
                    break;
                default:
                    break;

            }
        }
        return true;
    }

    @Override
    boolean skipString() throws ProtoException {
        int len = readRawVarint32();
        readString(len);
        return true;
    }
}
