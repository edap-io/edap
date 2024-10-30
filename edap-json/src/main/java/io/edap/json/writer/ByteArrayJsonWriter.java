/*
 * Copyright 2023 The edap Project
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

package io.edap.json.writer;

import io.edap.buffer.FastBuf;
import io.edap.io.BufOut;
import io.edap.io.ByteArrayBufOut;
import io.edap.json.Eson;
import io.edap.json.JsonEncoder;
import io.edap.json.JsonWriter;
import io.edap.util.Grisu;
import io.edap.util.Grisu3;
import io.edap.util.StringUtil;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;

import static io.edap.json.consts.JsonConsts.NEGATIVE_INFINITY_BYTES;
import static io.edap.json.consts.JsonConsts.POSITIVE_INFINITY_BYTES;
import static io.edap.util.FastNum.uncheckWriteInt;
import static io.edap.util.FastNum.uncheckWriteLong;
import static io.edap.util.StringUtil.IS_BYTE_ARRAY;
import static io.edap.util.StringUtil.isLatin1;
import static java.lang.Float.NEGATIVE_INFINITY;

/**
 * 使用Byte数组作为输出对象的JsonWriter的实现
 */
public class ByteArrayJsonWriter extends AbstractJsonWriter implements JsonWriter {

    byte[] buf;
    int pos;
    BufOut.WriteBuf wbuf;
    BufOut out;
    int wpos;

    Grisu3.FastDtoaBuilder builder = new Grisu3.FastDtoaBuilder();

    public ByteArrayJsonWriter(ByteArrayBufOut out) {
        this.out = out;
        buf  = out.getWriteBuf().bs;
        wbuf = out.getWriteBuf();
        pos  = 0;
        wpos = 0;
    }

    @Override
    public void write(boolean bool) {
        expand(5);
        if (bool) {
            buf[pos++] = 't';
            buf[pos++] = 'r';
            buf[pos++] = 'u';
            buf[pos++] = 'e';
        } else {
            buf[pos++] = 'f';
            buf[pos++] = 'a';
            buf[pos++] = 'l';
            buf[pos++] = 's';
            buf[pos++] = 'e';
        }
    }

    @Override
    public void write(Boolean bool) {
        if (bool == null) {
            writeNull();
        } else {
            write(bool.booleanValue());
        }
    }

    @Override
    public void write(byte b) {
        expand(1);
        buf[pos++] = b;
    }

    @Override
    public void write(byte b1, byte b2) {
        expand(2);
        buf[pos++] = b1;
        buf[pos++] = b2;
    }

    @Override
    public void write(byte b1, byte b2, byte b3) {
        expand(3);
        buf[pos++] = b1;
        buf[pos++] = b2;
        buf[pos++] = b3;
    }

    @Override
    public void write(int i) {
        expand(11);
        pos = uncheckWriteInt(buf, pos, i);
    }

    @Override
    public void write(Integer i) {
        if (i == null) {
            writeNull();
            return;
        }
        write(i.intValue());
    }

    @Override
    public void write(long l) {
        expand(20);
        pos = uncheckWriteLong(buf, pos, l);
    }

    @Override
    public void write(Long l) {
        if (l == null) {
            writeNull();
            return;
        }
        write(l.longValue());
    }

    @Override
    public void write(float f) {
        if (f == Float.POSITIVE_INFINITY) {
            write(POSITIVE_INFINITY_BYTES, 0, POSITIVE_INFINITY_BYTES.length);
        } else if (f == Float.NEGATIVE_INFINITY) {
            write(NEGATIVE_INFINITY_BYTES, 0, NEGATIVE_INFINITY_BYTES.length);
        } else if (f != f) {
            write((byte)'N', (byte)'a', (byte)'N');
        } else {
//            Grisu3.tryConvert(f, builder);
//            int len = builder.getLength();
//            expand(len);
//            pos += builder.copyTo(buf, pos);
//            expand(30);
//            pos += Grisu.fmt.doubleToBytes(buf, pos, f);
            byte[] bs = (String.valueOf(f)).getBytes();
            write(bs, 0, bs.length);
        }
    }

    @Override
    public void write(Float f) {
        if (f == null) {
            writeNull();
            return;
        }
        write(f.floatValue());
    }

    @Override
    public void write(double d) {
        if (d == Double.POSITIVE_INFINITY) {
            write(POSITIVE_INFINITY_BYTES, 0, POSITIVE_INFINITY_BYTES.length);
        } else if (d == Double.NEGATIVE_INFINITY) {
            write(NEGATIVE_INFINITY_BYTES, 0, NEGATIVE_INFINITY_BYTES.length);
        } else if (d != d) {
            write((byte)'N', (byte)'a', (byte)'N');
        } else {
//            Grisu3.tryConvert(d, builder);
//            int len = builder.getLength();
//            expand(len);
//            pos += builder.copyTo(buf, pos);
            expand(30);
            pos += Grisu.fmt.doubleToBytes(buf, pos, d);
        }
    }

    @Override
    public void write(Double d) {
        if (d == null) {
            writeNull();
            return;
        }
        write(d.doubleValue());
    }

    @Override
    public void write(byte b, int i) {
        expand(11);
        buf[pos++] = b;
        pos = uncheckWriteInt(buf, pos, i);
    }

    @Override
    public void write(byte b, Integer i) {
        if (i == null) {
            expand(5);
            int _pos = pos;
            buf[_pos++] = b;
            buf[_pos++] = 'n';
            buf[_pos++] = 'u';
            buf[_pos++] = 'l';
            buf[_pos++] = 'l';
            pos += 5;
            return;
        }
        expand(11);
        buf[pos++] = b;
        pos = uncheckWriteInt(buf, pos, i.intValue());
    }

    @Override
    public void write(byte b, long l) {
        expand(21);
        buf[pos++] = b;
        pos = uncheckWriteLong(buf, pos, l);
    }

    @Override
    public void write(byte b, Long l) {
        if (l == null) {
            expand(5);
            int _pos = pos;
            buf[_pos++] = b;
            buf[_pos++] = 'n';
            buf[_pos++] = 'u';
            buf[_pos++] = 'l';
            buf[_pos++] = 'l';
            pos += 5;
            return;
        }
        expand(21);
        buf[pos++] = b;
        pos = uncheckWriteLong(buf, pos, l);
    }

    @Override
    public void writeNull() {
        expand(4);
        int _pos = pos;
        buf[_pos++] = 'n';
        buf[_pos++] = 'n';
        buf[_pos++] = 'l';
        buf[_pos++] = 'l';
    }

    //@Override
    public void write(String s) {
        if (IS_BYTE_ARRAY && s.length() > 5) {
            if (isLatin1(s)) {
                writeLatin1(StringUtil.getValue(s));
            } else {
                writeString(s, 0, s.length());
            }
            return;
        }
        writeSlow(StringUtil.getCharValue(s));
    }

    private void writeUtf8(byte[] bs) {
        byte[] bytes = buf;
        int curr = pos;
        bytes[curr++] = '"';
        int len = bs.length;
        int b1, b2, b3, b4;
        for (int i = 0; i < len; i++) {
            b1 = bs[i++] & 0xFF;
            b2 = (bs[i] & 0xFF) << 8;
            char ch = (char) ((b1 | b2));
            switch (ch) {
                case '\n':
                    bytes[curr++] = '\\';
                    bytes[curr++] = 'n';
                    break;
                case '\r':
                    bytes[curr++] = '\\';
                    bytes[curr++] = 'r';
                    break;
                case '\t':
                    bytes[curr++] = '\\';
                    bytes[curr++] = 't';
                    break;
                case '\\':
                    bytes[curr++] = '\\';
                    bytes[curr++] = '\\';
                    break;
                case '"':
                    bytes[curr++] = '\\';
                    bytes[curr++] = '"';
                    break;
                default:
                    if (ch < 0x80) {
                        bytes[curr++] = (byte) ch;
                    } else if (ch < 0x800) {
                        bytes[curr++] = (byte) (0xc0 | (ch >> 6));
                        bytes[curr++] = (byte) (0x80 | (ch & 0x3f));
                    } else if (Character.isSurrogate(ch)) { //连取两个
                        i++;
                        b3 = bs[i++] & 0xFF;
                        b4 = (bs[i] & 0xFF) << 8;
                        char nextChar = (char) (b3 | b4);
                        int uc = Character.toCodePoint(ch, nextChar);
                        bytes[curr++] = (byte) (0xf0 | ((uc >> 18)));
                        bytes[curr++] = (byte) (0x80 | ((uc >> 12) & 0x3f));
                        bytes[curr++] = (byte) (0x80 | ((uc >> 6) & 0x3f));
                        bytes[curr++] = (byte) (0x80 | (uc & 0x3f));
                    } else {
                        bytes[curr++] = (byte) (0xe0 | ((ch >> 12)));
                        bytes[curr++] = (byte) (0x80 | ((ch >> 6) & 0x3f));
                        bytes[curr++] = (byte) (0x80 | (ch & 0x3f));
                    }
                    break;
            }
        }
        bytes[curr++] = '"';
        pos = curr;
    }

    private void writeLatin1(byte[] bs) {
        byte[] bytes = buf;
        int curr = pos;
        bytes[curr++] = '"';
        int blen = bs.length;
        for (int i=0;i<blen;i++) {
            byte b = bs[i];
            if (b == '"') {
                bytes[curr++] = '\\';
                bytes[curr++] = '"';
            } else if (b == '\\') {
                bytes[curr++] = '\\';
                bytes[curr++] = '\\';
            } else if (b < 32) {
                if (b == '\n') {
                    bytes[curr++] = '\\';
                    bytes[curr++] = 'n';
                } else if (b == '\r') {
                    bytes[curr++] = '\\';
                    bytes[curr++] = 'r';
                } else if (b == '\t') {
                    bytes[curr++] = '\\';
                    bytes[curr++] = 't';
                } else {
                    bytes[curr++] = b;
                }
            } else {
                bytes[curr++] = b;
            }
        }
        bytes[curr++] = '"';
        pos = curr;
    }

    //@Override
    public void writeSlow(char[] chars) {
        int slen = chars.length;
        expand((slen << 2) + (slen << 1) + 2);
        byte[] _buf = buf;
        int index = pos;
        _buf[index++] = '"';
        pos= index;
        int i = 0;
        //char[] cs = s.toCharArray();
        for (;i<slen;i++) {
            char c = chars[i];
//            if (c >= 128 || !CAN_DIRECT_WRITE[c]) {
//                break;
//            }
//            _buf[index++] = (byte) c;
//            if (c < 128 && CAN_DIRECT_WRITE[c]) {
//                _buf[index++] = (byte) c;
//            } else {
//                break;
//            }
            if (c > 31 && c != '"' && c != '\\' && c < 126) {
                _buf[index++] = (byte) c;
            } else {
                pos = index;
                writeString(chars, i, slen);
                return;
            }
        }
        _buf[index++] = '"';
        pos = index;
    }

    @Override
    public void writeField(byte[] bs, int offset, int end) {
        int len = end - offset;
        expand(len);
        int j = pos;
        int n = end;
        int i = offset;
        byte[] val = buf;   /* avoid getfield opcode */

        while (i < n) {
            val[j++] = bs[i++];
        }
        pos = j;
    }

    @Override
    public void write(byte[] bs, int offset, int length) {
        expand(length);
//        System.arraycopy(bs, offset, buf, pos, length);
//        pos += length;

        int j = pos;
        int n = offset + length;
        int i = offset;
        byte[] val = buf;   /* avoid getfield opcode */

        while (i < n) {
            val[j++] = bs[i++];
        }
        pos = j;
//        byte[] _buf = buf;
//        int cur = pos;
//        for (int i=offset;i<offset+length;i++) {
//            _buf[cur++] = bs[i];
//        }
//        pos = cur;
    }

    @Override
    public void write(BigDecimal bigDecimal) {
        if (bigDecimal == null) {
            writeNull();
            return;
        }
        byte[] bytes = bigDecimal.toString().getBytes(StandardCharsets.UTF_8);
        write(bytes, 0, bytes.length);
    }

    @Override
    public void writeObject(Object obj) {
        if (obj == null) {
            writeNull();
            return;
        }
        Eson.serialize(obj, this);
        //write(obj.toString());
    }

    @Override
    public void write(Object obj, JsonEncoder encoder) {
        encoder.encode(this, obj);
    }


    @Override
    public final void toStream(OutputStream stream) throws IOException {
        stream.write(this.buf, 0, this.pos);
    }

    @Override
    public byte[] toByteArray() {
        byte[] data = new byte[pos];
        System.arraycopy(buf, 0, data, 0, pos);
        return data;
    }

    @Override
    public int toFastBuf(FastBuf fastBuf) {
        throw new UnsupportedOperationException("");
    }

    @Override
    public void reset() {
        if (out != null) {
            out.reset();
        }
        pos = 0;
        wpos = 0;
    }

    @Override
    public void setWPos(int wPos) {
        this.wpos = wPos;
    }

    @Override
    public int size() {
        return pos;
    }

    public void expand(int minLength) {
        if (buf.length - pos < minLength) {
            if (wbuf.out.hasBuf()) {
                wbuf.out.write(wbuf.bs, 0, wbuf.start);
                wbuf.writeLen += wbuf.start;
                wbuf.start = 0;
            } else {
                int len = wbuf.len * 2;
                if (len < minLength + pos) {
                    len = minLength + pos;
                }
                byte[] res = new byte[len];
                System.arraycopy(buf, 0, res, 0, pos);

                wbuf.bs = res;
                wbuf.out.setLocalBytes(res);
                buf = res;
            }
        }
    }

    /**
     */
    private void writeString(String s, int start, int end) {
        byte[] _buf = buf;
        char c;
        int cur = pos;
        for (int i=start;i<end;i++) {
            c = s.charAt(i);
            if (c < 128) {
                if (CAN_DIRECT_WRITE[c]) {
                    _buf[cur++] = (byte) c;
                } else if (c == '"') {
                    _buf[cur++] = 92;
                    _buf[cur++] = 34;
                } else if (c == '\\') {
                    _buf[cur++] = 92;
                    _buf[cur++] = 92;
                } else {
                    byte[] tmp = REPLACEMENT_CHARS[c];
                    for (int j = 0; j < tmp.length; j++) {
                        _buf[cur++] = tmp[j];
                    }
                }
            } else if (c == '\u2028') {
                System.arraycopy(JS_REPLACEMENT_CHAR, 0, _buf, cur, 6);
                cur += 6;
            } else if (c == '\u2029') {
                System.arraycopy(JS_REPLACEMENT_CHAR, 6, _buf, cur, 6);
                cur += 6;
            } else if (c < 0x800) {
                _buf[cur++] = (byte) ((0xF << 6) | (c >>> 6));
                _buf[cur++] = (byte) (0x80       | (0x3F & c));
            } else if (c < Character.MIN_SURROGATE || Character.MAX_SURROGATE < c) {
                _buf[cur++] = (byte) ((0xF << 5) | (        c >>> 12));
                _buf[cur++] = (byte) (0x80       | (0x3F & (c >>> 6 )));
                _buf[cur++] = (byte) (0x80       | (0x3F &  c       ));
            } else {
                _buf[cur++] = (byte) (0xF0 | ((c >> 18) & 0x07));
                _buf[cur++] = (byte) (0x80 | ((c >> 12) & 0x3F));
                _buf[cur++] = (byte) (0x80 | ((c >> 6)  & 0x3F));
                _buf[cur++] = (byte) (0x80 | ( c        & 0x3F));
            }
        }
        _buf[cur++] = '"';
        pos = cur;
    }

    /**
     */
    private void writeString(char[] cs, int start, int end) {
        byte[] _buf = buf;
        char c;
        int cur = pos;
        for (int i=start;i<end;i++) {
            c = cs[i];
            if (c < 128) {
                if (CAN_DIRECT_WRITE[c]) {
                    _buf[cur++] = (byte) c;
                } else if (c == '"') {
                    _buf[cur++] = 92;
                    _buf[cur++] = 34;
                } else if (c == '\\') {
                    _buf[cur++] = 92;
                    _buf[cur++] = 92;
                } else {
                    byte[] tmp = REPLACEMENT_CHARS[c];
                    for (int j = 0; j < tmp.length; j++) {
                        _buf[cur++] = tmp[j];
                    }
                }
            } else if (c == '\u2028') {
                System.arraycopy(JS_REPLACEMENT_CHAR, 0, _buf, cur, 6);
                cur += 6;
            } else if (c == '\u2029') {
                System.arraycopy(JS_REPLACEMENT_CHAR, 6, _buf, cur, 6);
                cur += 6;
            } else if (c < 0x800) {
                _buf[cur++] = (byte) ((0xF << 6) | (c >>> 6));
                _buf[cur++] = (byte) (0x80       | (0x3F & c));
            } else if (c < Character.MIN_SURROGATE || Character.MAX_SURROGATE < c) {
                _buf[cur++] = (byte) ((0xF << 5) | (        c >>> 12));
                _buf[cur++] = (byte) (0x80       | (0x3F & (c >>> 6 )));
                _buf[cur++] = (byte) (0x80       | (0x3F &  c       ));
            } else {
                _buf[cur++] = (byte) (0xF0 | ((c >> 18) & 0x07));
                _buf[cur++] = (byte) (0x80 | ((c >> 12) & 0x3F));
                _buf[cur++] = (byte) (0x80 | ((c >> 6)  & 0x3F));
                _buf[cur++] = (byte) (0x80 | ( c        & 0x3F));
            }
        }
        _buf[cur++] = '"';
        pos = cur;
    }

}
