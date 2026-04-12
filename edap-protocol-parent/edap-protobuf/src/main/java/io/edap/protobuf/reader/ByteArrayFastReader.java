package io.edap.protobuf.reader;

import io.edap.protobuf.CodecType;
import io.edap.protobuf.ProtoBufDecoder;
import io.edap.protobuf.ProtoException;
import io.edap.protobuf.ext.AnyCodec;
import io.edap.protobuf.model.ProtoBufOption;
import io.edap.protobuf.wire.Field;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Stack;

import static io.edap.protobuf.wire.WireFormat.*;
import static io.edap.protobuf.wire.WireType.END_GROUP;
import static io.edap.protobuf.writer.FastProtoBufWriter.LATIN1_BYTE;
import static io.edap.protobuf.writer.FastProtoBufWriter.UTF16LE_BYTE;

public class ByteArrayFastReader extends ByteArrayReader {

    static ProtoBufOption PROTO_BUF_OPTION = new ProtoBufOption();

    static {
        PROTO_BUF_OPTION.setCodecType(CodecType.FAST);
    }

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
        } else if (len == 0) {
            return "";
        }
        int    tmpPos = pos;
        byte[] _buf   = buf;
        byte charsetByte = _buf[tmpPos++];
        String s;
        if (charsetByte == LATIN1_BYTE) {
            s = new String(_buf, tmpPos, len -1, StandardCharsets.ISO_8859_1);
        } else if (charsetByte == UTF16LE_BYTE) {
            s = new String(_buf, tmpPos, len -1, StandardCharsets.UTF_16LE);
        } else {
            s = new String(_buf, tmpPos, len -1, StandardCharsets.UTF_8);
        }
        pos += len;
        return s;
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

    @Override
    public Object readObject() throws ProtoException {
        return AnyCodec.decode(this, PROTO_BUF_OPTION);
    }
}
