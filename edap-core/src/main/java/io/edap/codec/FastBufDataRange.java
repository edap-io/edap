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

package io.edap.codec;

import io.edap.buffer.FastBuf;
import io.edap.util.StringUtil;

import java.nio.charset.Charset;

import static io.edap.util.Constants.*;

/**
 * FastBuf的数据块实现，使用FastBuf为内存缓存时协议解析时使用
 */
public class FastBufDataRange implements DataRange<Long, FastBuf> {

    FastBuf buf;

    boolean urlEncoded;

    boolean strict;

    long start;

    byte first;

    byte last;

    int length;

    int hash;

    public FastBufDataRange() {}

    public FastBufDataRange(FastBuf buf, long start, int length, int hash) {
        this.buf  = buf;
        this.hash = hash;
        first = buf.get(start);
        last  = buf.get(start+length-1);
    }

    public FastBufDataRange(FastBuf buf, int length, byte first, byte last, int hash) {
        this.buf    = buf;
        this.hash   = hash;
        this.first  = first;
        this.last   = last;
        this.length = length;
    }

    public static FastBufDataRange from(String v) {
        if (StringUtil.isEmpty(v)) {
            return null;
        }
        FastBufDataRange dr = new FastBufDataRange();
        byte[] bytes = v.getBytes(DEFAULT_CHARSET);
        long hashCode = FNV_1a_INIT_VAL;
        FastBuf buf = new FastBuf(bytes.length);
        buf.write(bytes,0, bytes.length);
        dr.start = buf.address();
        dr.first = bytes[0];
        dr.last  = bytes[bytes.length-1];
        dr.buf = buf;
        for (byte b : bytes) {
            hashCode ^= b;
            hashCode *= FNV_1a_FACTOR_VAL;
        }
        dr.length = bytes.length;
        dr.hash = (int)hashCode;
        return dr;
    }

    @Override
    public boolean urlEncoded() {
        return urlEncoded;
    }

    @Override
    public DataRange<Long, FastBuf> urlEncoded(boolean urlEncoded) {
        this.urlEncoded = urlEncoded;
        return this;
    }

    @Override
    public boolean matchStrict() {
        return strict;
    }

    @Override
    public DataRange<Long, FastBuf> matchStrict(boolean strict) {
        this.strict = strict;
        return this;
    }

    @Override
    public byte first() {
        return first;
    }

    @Override
    public DataRange<Long, FastBuf> first(byte first) {
        this.first = first;
        return this;
    }

    @Override
    public byte last() {
        return last;
    }

    @Override
    public DataRange<Long, FastBuf> last(byte last) {
        this.last = last;

        return this;
    }

    @Override
    public FastBuf buffer() {
        return buf;
    }

    @Override
    public DataRange<Long, FastBuf> buffer(FastBuf buf) {
        this.buf = buf;

        return this;
    }

    @Override
    public Long start() {
        return start;
    }

    @Override
    public DataRange<Long, FastBuf> start(Long start) {
        this.start = start;

        return this;
    }

    @Override
    public int hashCode() {
        return hash;
    }

   @Override
   public DataRange<Long, FastBuf> hashCode(int hash) {
        this.hash = hash;
        return this;
   }

    @Override
    public int length() {
        return length;
    }

    @Override
    public DataRange<Long, FastBuf> length(int length) {
        this.length = length;
        return this;
    }

    private boolean equalsLoose(FastBufDataRange dataRange) {
        if (dataRange == this) {
            return true;
        }
        if (dataRange.length != length) {
            return false;
        }
        if (dataRange.hash != hash) {
            return false;
        }
        if (dataRange.first != first) {
            return false;
        }
        return dataRange.last == last;
    }

    private boolean equalsStrict(FastBufDataRange dataRange) {
        if (dataRange == this) {
            return true;
        }
        if (dataRange.length != length) {
            return false;
        }
        if (dataRange.hash != hash) {
            return false;
        }
        if (dataRange.first != first) {
            return false;
        }
        if (dataRange.last != last) {
            return false;
        }
        if (length > 2) {
            for (int i=1;i<=length-2;i++) {
                FastBuf o = dataRange.buf;
                long pos = dataRange.start;
                if (buf.get(start+i) != o.get(pos+i)) {
                    return false;
                }
            }
        }
        return true;
    }

    @Override
    public boolean equals(Object obj) {
        if (!(obj instanceof FastBufDataRange)) {
            return false;
        }
        if (matchStrict()) {
            return equalsStrict((FastBufDataRange)obj);
        }
        return equalsLoose((FastBufDataRange)obj);
    }

    @Override
    public String getString(Charset charset) {
        byte[] data = new byte[length];
        for (int i=0;i<length;i++) {
            data[i] = buf.get(start+i);
        }
        return new String(data, charset);
    }


    @Override
    public void reset() {

    }
}
