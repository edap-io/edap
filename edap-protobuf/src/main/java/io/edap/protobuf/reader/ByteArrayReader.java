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

import io.edap.protobuf.ProtoBufDecoder;
import io.edap.protobuf.ProtoBufException;
import io.edap.protobuf.ext.AnyCodec;
import io.edap.protobuf.wire.Field.Type;

import java.util.ArrayList;
import java.util.List;

import static io.edap.protobuf.wire.WireFormat.FIXED_32_SIZE;
import static io.edap.protobuf.wire.WireFormat.FIXED_64_SIZE;

public class ByteArrayReader extends AbstractReader {
    protected final byte [] buf;
    protected       int     pos;
    protected       int     limit;
    protected final int     originalLimit;
    protected       char[]  tmp;

    public ByteArrayReader(byte [] buf) {
        this.buf   = buf;
        this.pos   = 0;
        this.limit = buf.length;
        this.originalLimit = limit;
    }

    public ByteArrayReader(byte [] buf, int offset, int len) {
        this.buf   = buf;
        this.limit = offset + len;
        this.pos   = offset;
        this.originalLimit = limit;
    }

    @Override
    public byte getByte(int p) {
        return buf[p];
    }

    @Override
    public byte getByte() {
        return buf[pos++];
    }

    @Override
    public int getPos() {
        return this.pos;
    }

    @Override
    public List<Integer> readPackedInt32(Type type) throws ProtoBufException {
        List<Integer> list = new ArrayList<>();
        int len = readRawVarint32();
        int old = pos;
        switch (type) {
            case INT32:
            case UINT32:
                while (pos - old < len) {
                    list.add(readRawVarint32());
                }
                break;
            case SINT32:
                while (pos - old < len) {
                    list.add(readSInt32());
                }
                break;
            case FIXED32:
            case SFIXED32:
                while (pos - old < len) {
                    list.add(readRawLittleEndian32());
                }
                break;
            default:
                throw ProtoBufException.malformedVarint();
        }
        return list;
    }

    protected void expandLocalIntegerArray(Integer [] intArray, int len) {
        if (len >= intArray.length) {
            Integer[] tmp2 = new Integer[len*2];
            System.arraycopy(intArray, 0, tmp2, 0, len);
            intArray = tmp2;
            LOCAL_TMP_INTEGER_ARRAY.set(intArray);
        }
    }

    protected void expandLocalIntArray(int [] intArray, int len) {
        if (len >= intArray.length) {
            int[] tmp2 = new int[len*2];
            System.arraycopy(intArray, 0, tmp2, 0, len);
            intArray = tmp2;
            LOCAL_TMP_INT_ARRAY.set(intArray);
        }
    }

    @Override
    public Integer[] readPackedInt32Array(Type type) throws ProtoBufException {
        Integer[] tmp = LOCAL_TMP_INTEGER_ARRAY.get();
        int len = readRawVarint32();
        expandLocalIntegerArray(tmp, len);
        int old = pos;
        int i = 0;
        switch (type) {
            case INT32:
            case UINT32:
                while (pos - old < len) {
                    tmp[i++] = readRawVarint32();
                }
                break;
            case SINT32:
                while (pos - old < len) {
                    tmp[i++] = readSInt32();
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
    public int[] readPackedInt32ArrayValue(Type type) throws ProtoBufException {
        int[] tmp = LOCAL_TMP_INT_ARRAY.get();
        int len = readRawVarint32();
        expandLocalIntArray(tmp, len);
        int old = pos;
        int i = 0;
        switch (type) {
            case INT32:
            case UINT32:
                while (pos - old < len) {
                    tmp[i++] = readRawVarint32();
                }
                break;
            case SINT32:
                while (pos - old < len) {
                    tmp[i++] = readSInt32();
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

        int [] res = new int[i];
        System.arraycopy(tmp, 0, res, 0, i);
        return res;
    }

    public boolean[] readPackedBoolValues() throws ProtoBufException {
        int len = readRawVarint32();
        boolean[] vs = new boolean[len];
        for (int i=0;i<len;i++) {
            vs[i] = readUInt32()==1?true:false;
        }
        return vs;
    }

    public Boolean[] readPackedBools() throws ProtoBufException {
        int len = readRawVarint32();
        Boolean[] vs = new Boolean[len];
        for (int i=0;i<len;i++) {
            vs[i] = readUInt32()==1?true:false;
        }
        return vs;
    }


    protected void expandLocalLongArray(Long [] intArray, int len) {
        if (len >= intArray.length) {
            Long[] tmp2 = new Long[len*2];
            System.arraycopy(intArray, 0, tmp2, 0, len);
            intArray = tmp2;
            LOCAL_TMP_LONG_ARRAY.set(intArray);
        }
    }

    protected void expandLocalLongValueArray(long [] intArray, int len) {
        if (len >= intArray.length) {
            long[] tmp2 = new long[len*2];
            System.arraycopy(intArray, 0, tmp2, 0, len);
            intArray = tmp2;
            LOCAL_TMP_LONG_VALUE_ARRAY.set(intArray);
        }
    }

    @Override
    public Long[] readPackedInt64Array(Type type) throws ProtoBufException {
        Long[] tmp = LOCAL_TMP_LONG_ARRAY.get();
        int len = readRawVarint32();
        int old = pos;
        int i = 0;
        switch (type) {
            case INT64:
            case UINT64:
                while (pos - old < len) {
                    tmp[i++] = readRawVarint64();
                    expandLocalLongArray(tmp, i);
                }
                break;
            case SINT64:
                while (pos - old < len) {
                    tmp[i++] = readSInt64();
                    expandLocalLongArray(tmp, i);
                }
                break;
            case FIXED64:
            case SFIXED64:
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
    public long[] readPackedInt64ArrayValue(Type type) throws ProtoBufException {
        long[] tmp = LOCAL_TMP_LONG_VALUE_ARRAY.get();
        int len = readRawVarint32();
        int old = pos;
        int i = 0;
        switch (type) {
            case INT64:
            case UINT64:
                while (pos - old < len) {
                    tmp[i++] = readRawVarint64();
                    expandLocalLongValueArray(tmp, i);
                }
                break;
            case SINT64:
                while (pos - old < len) {
                    tmp[i++] = readSInt64();
                    expandLocalLongValueArray(tmp, i);
                }
                break;
            case FIXED64:
            case SFIXED64:
                while (pos - old < len) {
                    tmp[i++] = readSFixed64();
                    expandLocalLongValueArray(tmp, i);
                }
                break;
            default:
                throw ProtoBufException.malformedVarint();
        }

        long [] res = new long[i];
        System.arraycopy(tmp, 0, res, 0, i);
        return res;
    }

    @Override
    public List<Long> readPackedInt64(Type type)
            throws ProtoBufException {
        List<Long> list = new ArrayList<>();
        int len = readRawVarint32();
        int old = pos;
        switch (type) {
            case INT64:
            case UINT64:
                while (pos - old < len) {
                    list.add(readRawVarint64());
                }
                break;
            case SINT64:
                while (pos - old < len) {
                    list.add(readSInt64());
                }
                break;
            case FIXED64:
            case SFIXED64:
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
    public byte[] readBytes() throws ProtoBufException {
        int len = readRawVarint32();
        byte[] bs = new byte[len];
        System.arraycopy(buf, pos, bs, 0, len);
        pos += len;
        return bs;
    }

    @Override
    public String readString() throws ProtoBufException {
        int len = readRawVarint32();
        if (len < 0) {
            return null;
        }
        char[] cs = null;
        if (len < 4096 && tmp == null) {
            tmp = LOCAL_TMP_CHAR_ARRAY.get();
        }
        cs = tmp;
        if (cs == null) {
            cs = new char[len];
        }
        int index = 0;
        int tmpPos = pos;
        for (int i=0;i<len;i++) {
            int b = buf[tmpPos++];
            if ((b & 0x80) == 0) {
                cs[index++] = (char)b;
            } else {
                byte b2 = buf[tmpPos++];
                i++;
                if ((b & 0xE0) == 0xC0) {
                    cs[index++] = (char)(((b & 0x1F) << 6) + (b2 & 0x3F));
                } else {
                    byte b3 = buf[tmpPos++];
                    i++;
                    if ((b & 0xF0) == 0xE0) {
                        cs[index++] = (char)(((b & 0x0F) << 12)
                                + ((b2 & 0x3F) << 6) + (b3 & 0x3F));
                    } else {
                        byte b4 = buf[tmpPos++];
                        i++;
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
        //String s = new String(buf, pos, len, CHARSET_UTF8);
        pos += len;
        return s;
    }

    @Override
    public String readString(int charLen) throws ProtoBufException {
        if (charLen < 0) {
            return null;
        } else if (charLen == 0) {
            return "";
        }
        //char[] cs = new char[charLen];
        int count = 0;
        int oldPos = pos;
        int tmpPos = pos;
        byte[] _tmp = buf;
        while (count < charLen) {
            int b = _tmp[tmpPos++];
            if ((b & 0x80) == 0) {
                count++;
            } else {
                byte b2 = _tmp[tmpPos++];
                if ((b & 0xE0) == 0xC0) {
                    count++;
                } else {
                    byte b3 = _tmp[tmpPos++];
                    if ((b & 0xF0) == 0xE0) {
                        count++;
                    } else {
                        byte b4 = _tmp[tmpPos++];
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
                            count++;
                            count++;
                        }
                    }
                }
            }
        }
        String s = new String(_tmp, oldPos, tmpPos - oldPos, CHARSET_UTF8);
        pos = tmpPos;
        //String s = new String(buf, pos, len, CHARSET_UTF8);
        return s;
    }

    @Override
    public Object readObject() throws ProtoBufException {
        return AnyCodec.decode(this);
    }

    @Override
    boolean skipRawVarint() throws ProtoBufException {
        int len = limit - pos;
        for (int i = 0; i < len; i++) {
            if (buf[pos++] >= 0) {
                return true;
            }
        }
        throw ProtoBufException.malformedVarint();
    }

    @Override
    boolean skipRawBytes(int len) throws ProtoBufException {
        if (limit - pos >= len) {
            pos += len;
            return true;
        }
        throw ProtoBufException.malformedVarint();
    }

    @Override
    boolean skipMessage() throws ProtoBufException {
        int len = readRawVarint32();
        return skipRawBytes(len);
    }


    @Override
    int readRawVarint32() throws ProtoBufException {
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

    @Override
    long readRawVarint64() throws ProtoBufException {
        fastpath: {
            int tmpPos = pos;
            if (tmpPos == limit) {
                break fastpath;
            }
            long x;
            int y;
            if ((y = buf[tmpPos++]) >= 0) {
                pos = tmpPos;
                return y;
            } else if (limit - tmpPos < 9) {
                break fastpath;
            } else if ((y ^= (buf[tmpPos++] << 7)) < 0) {
                x = y ^ (~0 << 7);
            } else if ((y ^= buf[tmpPos++] << 14) >= 0) {
                x = y ^ ((~0 << 7) ^ (~0 << 14));
            } else if ((y ^= buf[tmpPos++] << 21) < 0) {
                x = y ^ ((~0 << 7) ^ (~0 << 14) ^ (~0 << 21));
            } else if ((x = y ^ ((long)buf[tmpPos++] << 28)) >= 0L) {
                x ^= (~0L << 7) ^ (~0L << 14) ^ (~0L << 21) ^ (~0L << 28);
            } else if ((x ^= ((long)buf[tmpPos++] << 35)) < 0L) {
                x ^= (~0L << 7)
                        ^ (~0L << 14)
                        ^ (~0L << 21)
                        ^ (~0L << 28)
                        ^ (~0L << 35);
            } else if ((x ^= ((long)buf[tmpPos++] << 42)) >= 0L) {
                x ^= (~0L << 7)
                        ^ (~0L << 14)
                        ^ (~0L << 21)
                        ^ (~0L << 28)
                        ^ (~0L << 35)
                        ^ (~0L << 42);
            } else if ((x ^= ((long)buf[tmpPos++] << 49)) < 0L) {
                x ^= (~0L << 7)
                        ^ (~0L << 14)
                        ^ (~0L << 21)
                        ^ (~0L << 28)
                        ^ (~0L << 35)
                        ^ (~0L << 42)
                        ^ (~0L << 49);
            } else {
                x ^= ((long) buf[tmpPos++] << 56);
                x ^= (~0L << 7)
                        ^ (~0L << 14)
                        ^ (~0L << 21)
                        ^ (~0L << 28)
                        ^ (~0L << 35)
                        ^ (~0L << 42)
                        ^ (~0L << 49)
                        ^ (~0L << 56);
                if (x < 0L) {
                    if (buf[tmpPos++] < 0L) {
                        break fastpath;
                    }
                }
            }
            pos = tmpPos;
            return x;
        }
        return readRawVarint64SlowPath();
    }

    private long readRawVarint64SlowPath() throws ProtoBufException {
        long result = 0;
        for (int shift = 0; shift < 64 && pos < limit; shift += 7) {
            final byte b = buf[pos++];
            result |= (long) (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                return result;
            }
        }
        throw ProtoBufException.malformedVarint();
    }

    @Override
    long readRawLittleEndian64() throws ProtoBufException {
        if (limit - pos < FIXED_64_SIZE) {
            throw ProtoBufException.truncatedMessage();
        }
        byte[] _buf = buf;
        int p = pos;
        pos += 8;
        return (  ((_buf[p++] & 0xffL))
                | ((_buf[p++] & 0xffL) << 8)
                | ((_buf[p++] & 0xffL) << 16)
                | ((_buf[p++] & 0xffL) << 24)
                | ((_buf[p++] & 0xffL) << 32)
                | ((_buf[p++] & 0xffL) << 40)
                | ((_buf[p++] & 0xffL) << 48)
                | ((_buf[p++] & 0xffL) << 56));
    }

    @Override
    int readRawLittleEndian32() throws ProtoBufException {
        if (limit - pos < FIXED_32_SIZE) {
            throw ProtoBufException.truncatedMessage();
        }
        return   (((buf[pos++] & 0xFF))
                | ((buf[pos++] & 0xFF) << 8)
                | ((buf[pos++] & 0xFF) << 16)
                | ((buf[pos++] & 0xFF) << 24));
    }



    @Override
    public int readTag() throws ProtoBufException {
        if (pos >= limit) {
            return 0;
        }
        return readInt32();
    }

    @Override
    public <T> T readMessage(ProtoBufDecoder<T> codec) throws ProtoBufException {
        int len = readUInt32();
        int oldLimit = limit;

        limit = pos + len;
        if (limit > originalLimit) {
            throw ProtoBufException.truncatedMessage();
        }
        T t = codec.decode(this);
        limit = oldLimit;
        return t;
    }

    @Override
    public void reset() {
        pos = 0;
    }
}
