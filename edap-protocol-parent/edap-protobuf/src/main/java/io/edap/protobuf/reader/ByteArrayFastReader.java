/*
 * Copyright 2020 The edap Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package io.edap.protobuf.reader;

import io.edap.protobuf.CodecType;
import io.edap.protobuf.ProtoBufDecoder;
import io.edap.protobuf.ProtoException;
import io.edap.protobuf.ext.AnyCodec;
import io.edap.protobuf.model.ProtoBufOption;

import io.edap.util.StringUtil;

import java.nio.charset.StandardCharsets;

import java.util.Stack;

import static io.edap.protobuf.wire.WireFormat.*;
import static io.edap.protobuf.wire.WireType.END_GROUP;
import static io.edap.protobuf.writer.FastProtoBufWriter.LATIN1_BYTE;
import static io.edap.protobuf.writer.FastProtoBufWriter.UTF16BE_BYTE;
import static io.edap.protobuf.writer.FastProtoBufWriter.UTF16LE_BYTE;
import static io.edap.protobuf.writer.FastProtoBufWriter.UTF8_BYTE;
import static io.edap.util.StringUtil.NATIVE_LITTLE_ENDIAN;

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
        } else if (charsetByte == UTF16LE_BYTE || charsetByte == UTF16BE_BYTE) {
            byte[] sdata = new byte[len -1];
            System.arraycopy(_buf, tmpPos, sdata, 0, len - 1);
            // ★ UTF16 标记携带的是「发送方的字节序」⇒ 与本机不一致时翻一次。
            //   同端序 ⇒ 不翻（零成本）；跨端序 ⇒ 翻一次（比重新解码便宜两个数量级）。
            boolean wireLittleEndian = (charsetByte == UTF16LE_BYTE);
            if (wireLittleEndian != NATIVE_LITTLE_ENDIAN) {
                StringUtil.swapUtf16ByteOrder(sdata, 0, len - 1);
            }
            // 第二个参数是 JDK 的 coder（1 = UTF16），与 wire 上的字节序无关
            s = StringUtil.fastInstance(sdata, UTF16LE_BYTE);
        } else if (charsetByte == UTF8_BYTE) {
            s = new String(_buf, tmpPos, len -1, StandardCharsets.UTF_8);
        } else {
            throw new ProtoException("未知的 charset 标记: " + charsetByte);
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
