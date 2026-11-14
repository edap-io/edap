
/*
 * Copyright 2023 The edap Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package io.edap.protobuf.writer;

import io.edap.buffer.FastBuf;
import io.edap.io.BufOut;
import io.edap.protobuf.ProtoBufEnum;
import io.edap.protobuf.ProtoBufWriter;
import io.edap.protobuf.wire.Field;
import io.edap.util.CollectionUtils;
import io.edap.util.UnsafeUtil;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

import static io.edap.protobuf.ProtoBufWriter.encodeZigZag32;
import static io.edap.protobuf.ProtoBufWriter.encodeZigZag64;
import static io.edap.protobuf.wire.WireFormat.*;
import static io.edap.util.CollectionUtils.isEmpty;
import static io.edap.util.StringUtil.*;


public abstract class AbstractWriter implements ProtoBufWriter {

    static final ThreadLocal<FastBuf> LOCAL_FAST_BUF =
            new ThreadLocal<FastBuf>() {
                @Override
                protected FastBuf initialValue() {
                    return new FastBuf(4096);
                }
            };

    public BufOut.WriteBuf wbuf;
    protected BufOut out;
    protected byte[] bs;
    protected int pos;

    public AbstractWriter(BufOut out) {
        this.out = out;
        wbuf = out.getWriteBuf();
        bs = wbuf.bs;
        pos = wbuf.start;
    }

    @Override
    public void setPos(int pos) {
        this.pos = pos;
    }

    @Override
    public int size() {
        return pos;
    }

    public int getPos() {
        return pos;
    }

    @Override
    public BufOut getBufOut() {
        return out;
    }

    @Override
    public byte[] buffer() {
        return bs;
    }

    @Override
    public void toStream(OutputStream stream) throws IOException {
        stream.write(bs, 0, pos);
    }

    @Override
    public byte[] toByteArray() {
        byte[] data = new byte[pos];
        System.arraycopy(bs, 0, data, 0, pos);
        return data;
    }

    public void writeFieldData(final byte[] fieldData) {
        int len = fieldData.length;
        byte[] _bs = bs;
        int _p = pos;
        if (len == 1) {
            _bs[_p++] = fieldData[0];
        } else if (len == 2) {
            _bs[_p++] = fieldData[0];
            _bs[_p++] = fieldData[1];
        } else {
            for (int i = 0; i < len; i++) {
                _bs[_p++] = fieldData[i];
            }
        }
        pos = _p;
    }

    public void writeFieldData2(final byte[] fieldData) {
        int len = fieldData.length;
        if (len == 1) {
            bs[pos++] = fieldData[0];
        } else if (len == 2) {
            bs[pos++] = fieldData[0];
            bs[pos++] = fieldData[1];
        } else {
            for (int i = 0; i < len; i++) {
                bs[pos++] = fieldData[i];
            }
        }
    }

    @Override
    public void writeByte(final byte b) {
        expand(1);
        this.bs[pos++] = b;
    }

    @Override
    public void writeBool(final byte[] fieldData, final Boolean value) {
        if (value == null) {
            return;
        }
        writeBool(fieldData, value.booleanValue());
    }

    @Override
    public void writeBool(final byte[] fieldData, final boolean value) {
        if (!value) {
            return;
        }
        expand(fieldData.length + 1);
        writeFieldData(fieldData);
        bs[pos++] = (byte) (value ? 1 : 0);
    }

    @Override
    public void writeInt32(final byte[] fieldData, final Integer value) {
        if (value == null) {
            return;
        }
        writeInt32(fieldData, value.intValue());
    }

    @Override
    public void writeInt32(final byte[] fieldData, final int value) {
        if (value == 0) {
            return;
        }
        if (value > 0) {
            expand(MAX_VARLONG_SIZE);
            writeFieldData(fieldData);
            writeUInt32_0(value);
        } else {
            expand(MAX_VARLONG_SIZE + MAX_VARINT_SIZE);
            writeByteArray_0(fieldData, 0, fieldData.length);
            writeUInt64_0(value);
        }
    }

    @Override
    public void writeUInt32(final byte[] fieldData, final Integer value) {
        if (value == null) {
            return;
        }
        writeUInt32(fieldData, value.intValue());
    }

    @Override
    public void writeUInt32(final byte[] fieldData, final int value) {
        if (value == 0) {
            return;
        }
        expand(MAX_VARLONG_SIZE);
        writeFieldData(fieldData);
        writeUInt32_0(value);
    }

    @Override
    public final void writeInt32(int value) {
        if (0 == value) {
            return;
        }
        if (value >= 0) {
            expand(MAX_VARINT_SIZE);
            writeUInt32_0(value);
        } else {
            expand(MAX_VARLONG_SIZE);
            writeUInt64_0(value);
        }
    }

    @Override
    public void writeInt32(int value, boolean needEncodeZero) {
        if (!needEncodeZero && value == 0) {
            return;
        }
        if (value >= 0) {
            expand(MAX_VARINT_SIZE);
            writeUInt32_0(value);
        } else {
            expand(MAX_VARLONG_SIZE);
            writeUInt64_0(value);
        }
    }

    public final void writeInt32_0(int value) {
        if (value > 0) {
            writeUInt32_0(value);
        } else {
            writeUInt64_0(value);
        }
    }

    @Override
    public final void writeUInt32(int value) {
        expand(MAX_VARINT_SIZE);
        writeUInt32_0(value);
    }

    @Override
    public final void writeSInt32(final byte[] fieldData, final Byte value) {
        if (value == null) {
            return;
        }
        expand(MAX_VARLONG_SIZE);
        writeFieldData(fieldData);
        writeUInt32_0(encodeZigZag32(value));
    }

    @Override
    public final void writeSInt32(final byte[] fieldData, final byte value) {
        writeSInt32(fieldData, (int)value);
    }

    @Override
    public final void writeSInt32(final byte[] fieldData, final Short value) {
        if (value == null) {
            return;
        }
        expand(MAX_VARLONG_SIZE);
        writeFieldData(fieldData);
        writeUInt32_0(encodeZigZag32(value));
    }

    @Override
    public final void writeSInt32(final byte[] fieldData, final short value) {
        writeSInt32(fieldData, (int)value);
    }

    @Override
    public final void writeSInt32(final byte[] fieldData, final Character value) {
        if (value == null) {
            return;
        }
        expand(MAX_VARLONG_SIZE);
        writeFieldData(fieldData);
        writeUInt32_0(encodeZigZag32(value));
    }

    @Override
    public final void writeSInt32(final byte[] fieldData, final char value) {
        writeSInt32(fieldData, (int)value);
    }

    @Override
    public final void writeSInt32(final byte[] fieldData, final Integer value) {
        if (value == null) {
            return;
        }
        writeSInt32(fieldData, value.intValue());
    }

    @Override
    public void writeSInt32(final byte[] fieldData, final int value) {
        if (0 == value) {
            return;
        }
        expand(MAX_VARLONG_SIZE);
        writeFieldData(fieldData);
        writeUInt32_0(encodeZigZag32(value));
    }

    @Override
    public void writeSInt32(final int value, boolean needEncoderZero) {
        if (0 == value && !needEncoderZero) {
            return;
        }
        expand(MAX_VARLONG_SIZE);
        writeUInt32_0(encodeZigZag32(value));
    }

    @Override
    public void writeFixed32(final byte[] fieldData, final Integer value) {
        if (value == null || value.intValue() == 0) {
            return;
        }
        expand(fieldData.length + FIXED_32_SIZE);
        writeFieldData(fieldData);
        writeFixed32_0(value);
    }

    @Override
    public void writeFixed32(final byte[] fieldData, final int value) {
        if (value == 0) {
            return;
        }
        expand(fieldData.length + FIXED_32_SIZE);
        writeFieldData(fieldData);
        writeFixed32_0(value);
    }

    @Override
    public void writeSFixed32(final byte[] fieldData, final Integer value) {
        if (value == null || value.intValue() == 0) {
            return;
        }
        writeFixed32(fieldData, value);
    }

    @Override
    public void writeSFixed32(final byte[] fieldData, final int value) {
        writeFixed32(fieldData, value);
    }

    @Override
    public void writeLong(byte[] fieldData, Long value) {
        if (value == null || value.longValue() == 0) {
            return;
        }
        writeLong(fieldData, value.longValue());
    }

    @Override
    public void writeLong(byte[] fieldData, long value) {
        writeInt64(fieldData, value);
    }

    @Override
    public final void writeInt64(final byte[] fieldData, final long value) {
        writeUInt64(fieldData, value);
    }

    @Override
    public void writeUInt64(final byte[] fieldData, final Long value) {
        if (value == null || value.longValue() == 0) {
            return;
        }
        expand(MAX_VARINT_SIZE + MAX_VARLONG_SIZE);
        writeFieldData(fieldData);
        writeUInt64_0(value);
    }

    @Override
    public void writeUInt64(final byte[] fieldData, final long value) {
        if (value == 0) {
            return;
        }
        expand(MAX_VARINT_SIZE + MAX_VARLONG_SIZE);
        writeFieldData(fieldData);
        writeUInt64_0(value);
    }

    @Override
    public final void writeSInt64(final byte[] fieldData, final Long value) {
        if (value == null || value.longValue() == 0) {
            return;
        }
        writeUInt64(fieldData, encodeZigZag64(value.longValue()));
    }

    @Override
    public final void writeSInt64(final byte[] fieldData, final long value) {
        writeUInt64(fieldData, encodeZigZag64(value));
    }

    @Override
    public void writePackedBools(byte[] fieldData, List<Boolean> values) {
        if (isEmpty(values)) {
            return;
        }
        expand(MAX_VARINT_SIZE * 2 + values.size());
        int size = values.size();
        writeFieldData(fieldData);
        writeInt32_0(size);
        for (int i=0;i<size;i++) {
            if (values.get(i)) {
                writeByte((byte) 1);
            } else {
                writeByte((byte)0);
            }
        }
    }

    @Override
    public void writePackedBools(byte[] fieldData, Iterable<Boolean> values) {
        if (values == null || !values.iterator().hasNext()) {
            return;
        }
        List<Integer> vs = new ArrayList<>();
        for (Boolean v : values) {
            vs.add(v!=null&&v.booleanValue()?1:0);
        }
        writePackedInts(fieldData, vs, Field.Type.UINT32);
    }

    @Override
    public void writeFixed64(final byte[] fieldData, final Long value) {
        if (value == null) {
            return;
        }
        writeFixed64(fieldData, value.longValue());
    }

    @Override
    public void writeFixed64(final byte[] fieldData, final long value) {
        if (value == 0) {
            return;
        }
        expand(MAX_VARINT_SIZE + FIXED_64_SIZE);
        writeFieldData(fieldData);
        writeFixed64_0(value);
    }

    @Override
    public final void writeSFixed64(final byte[] fieldData, final Long value) {
        if (value == null) {
            return;
        }
        writeFixed64(fieldData, value);
    }

    @Override
    public final void writeSFixed64(final byte[] fieldData, final long value) {
        writeFixed64(fieldData, value);
    }

    @Override
    public final void writeFloat(final byte[] fieldData, final Float value) {
        if (value == null) {
            return;
        }
        writeFixed32(fieldData, Float.floatToRawIntBits(value));
    }

    @Override
    public final void writeFloat(final byte[] fieldData, final float value) {
        writeFixed32(fieldData, Float.floatToRawIntBits(value));
    }

    @Override
    public final void writeDouble(final byte[] fieldData, final Double value) {
        if (value == null) {
            return;
        }
        writeFixed64(fieldData, Double.doubleToRawLongBits(value));
    }

    @Override
    public final void writeDouble(final byte[] fieldData, final double value) {
        writeFixed64(fieldData, Double.doubleToRawLongBits(value));
    }

    @Override
    public final void writeEnum(final byte[] fieldData, Integer value) {
        if (value == null) {
            return;
        }
        writeUInt32(fieldData, value.intValue());
    }

    @Override
    public <E extends Enum<E>> void writeListEnum(byte[] fieldData, List<E> vs) {
        if (isEmpty(vs)) {
            return;
        }
        int size = vs.size();
        int[] values = new int[size];
        for (int i=0;i<size;i++) {
            values[i] = vs.get(i).ordinal();
        }
        writePackedInts(fieldData, values, Field.Type.UINT32);
    }

    @Override
    public <E extends Enum<E>> void writeListEnum(byte[] fieldData, Iterable<E> vs) {
        if (vs == null || !vs.iterator().hasNext()) {
            return;
        }
        List<Integer> values = new ArrayList<>();
        int i = 0;
        for (E e : vs) {
            values.add(e.ordinal());
        }
        writePackedInts(fieldData, values, Field.Type.UINT32);
    }

    @Override
    public <E extends ProtoBufEnum> void writeListProtoEnum(byte[] fieldData, List<E> vs) {
        if (CollectionUtils.isEmpty(vs)) {
            return;
        }
        int size = vs.size();
        int[] values = new int[size];
        for (int i=0;i<size;i++) {
            values[i] = vs.get(i).getValue();
        }
        writePackedInts(fieldData, values, Field.Type.UINT32);
    }

    @Override
    public <E extends ProtoBufEnum> void writeListProtoEnum(byte[] fieldData, Iterable<E> vs) {
        if (vs == null || !vs.iterator().hasNext()) {
            return;
        }
        List<Integer> values = new ArrayList<>();
        for (E e : vs) {
            values.add(e.getValue());
        }
        writePackedInts(fieldData, values, Field.Type.UINT32);
    }

    @Override
    public void writeString(final byte[] fieldData, final String value) {
        if (value == null || value.length() == 0) {
            return;
        }
        expand(fieldData.length);
        writeFieldData(fieldData);
        writeString(value);
    }

    @Override
    public void writeStringUtf8(final String value, int len) {
        String v = value;
        int charLen = v.length();
        // 如果jvm是9以上版本，并且字符串为Latin1的编码，取字符串对象内部的value字节数组做UTF-8编码
        if (IS_BYTE_ARRAY && isLatin1(v) && charLen > 5) {
            // ★ 同 writeString0：Latin1 的高位字节不是合法 UTF-8，必须做 UTF-8 编码
            byte[] utf8 = v.getBytes(UTF8_CHARSET);
            expand(utf8.length);
            writeByteArray_0(utf8, 0, utf8.length);
            return;
        }
        // 转为utf8后最大的所需字节数
        int maxBytes = charLen * 3;
        // 如果所需最大字节数小于3k + 编码int最大字节数 则直接扩容所需最大字节数
        if (maxBytes <= 3072) {
            expand(maxBytes + 1);
            int _pos = pos;
            pos += writeChars(v, 0, charLen, _pos);
            return;
        }

        // 每次取最大为1024个字符进行写入
        int start = 0;
        int end = Math.min((start + 1024), charLen);

        int oldPos = pos;
        int _pos   = oldPos + 1;
        expand(maxBytes);
        while (start < charLen) {
            expand(3072);
            _pos += writeChars(v, start, end, _pos);
            pos = _pos;
            start += 1024;
            end = Math.min((start + 1024), charLen);
        }
    }

    @Override
    public void writeString(final String value) {
        if (value == null) {
            writeInt32(-1);
            return;
        }
        //char[] cs = (char[])UnsafeMemory.getValue(value, StringUtil.STRING_VALUE_OFFSET);
        int charLen = value.length();
        if (charLen == 0) {
            writeInt32(0, true);
            return;
        }
        writeString0(value);
    }

    /**
     * 将不为空的字符串写入到缓存中
     * @param value
     */
    private final void writeString0(final String value) {
        String v = value;
        int charLen = v.length();
        // 如果jvm是9以上版本，并且字符串为Latin1的编码，取字符串对象内部的value字节数组做UTF-8编码
        if (IS_BYTE_ARRAY && isLatin1(v)) {
            // ★ Latin1 的高位字节（0x80~0xFF）不是合法的 UTF-8
            //   （例：'é' 的 Latin1 字节是 0xE9，而 UTF-8 是 0xC3 0xA9）
            //   —— 直接裸拷内部 byte[] 会写出非法 UTF-8，解码端按 3 字节序列解会乱码/越界。
            //   ⇒ 必须让 JDK 做 UTF-8 编码；纯 ASCII 的 Latin1 串走 JDK 的向量化快路径。
            //
            // ★★ 第五轮：纯 ASCII 的 Latin1 串<b>连 JDK 的编码都不用做</b>——
            //   它的 UTF-8 字节与内部字节<b>完全相同</b>，可以直接拷进输出缓冲，
            //   省掉 {@code getBytes(UTF_8)} 的「一次分配 + 一次拷贝」。
            //   「是不是纯 ASCII」用 JDK 自己的 SIMD 判定（{@link StringUtil#hasHighBytes}）
            //   —— O(n/16)，比逐字节扫快一个数量级。
            byte[] latin1 = getLatin1Bytes(v);
            if (latin1 != null && !hasHighBytes(latin1, 0, latin1.length)) {
                writeByteArray(latin1, 0, latin1.length);
                return;
            }
            byte[] utf8 = v.getBytes(UTF8_CHARSET);
            writeByteArray(utf8, 0, utf8.length);
            return;
        }
        // 转为utf8后最大的所需字节数
        int maxBytes = charLen * 3;
        // ★ 预留长度前缀的字节数。UTF-8 编码后实际长度 ≤ maxBytes ⇒ 实际 varint 长度 ≤ reserve；
        //   绝大多数情况（中文串，每字 3 字节）实际长度 == maxBytes ⇒ varint 长度 == reserve
        //   ⇒ 内容可以直接写在「前缀位置 + reserve」处，编码完再回填前缀，<b>省掉整块右移的 memmove</b>。
        int reserve = varintLen(maxBytes);
        // 如果所需最大字节数小于3k + 编码int最大字节数 则直接扩容所需最大字节数
        if (maxBytes <= 3072) {
            expand(maxBytes + reserve);
            int _pos = pos;
            int len = writeChars(getCharValue(v), 0, charLen, _pos + reserve);
            pos = _pos + writeStringLenPrefix(_pos, reserve, len);
            return;
        }

        // 每次取最大为1024个字符进行写入
        int start = 0;
        int end = Math.min((start + 1024), charLen);

        int oldPos = pos;
        expand(maxBytes + reserve);
        int _pos   = oldPos + reserve;
		char[] chars = getCharValue(v);
        while (start < charLen) {
            expand(3072);
            _pos += writeChars(chars, start, end, _pos);
            pos = _pos;
            start += 1024;
            end = Math.min((start + 1024), charLen);
        }
        int len = _pos - oldPos - reserve;
        pos = oldPos + writeStringLenPrefix(oldPos, reserve, len);
    }

    /**
     * 回填字符串的长度前缀（varint），返回「前缀 + 内容」的总字节数。
     *
     * <p>内容已经写在 {@code prefixPos + reserve} 处（{@code reserve} 是预留的前缀字节数）。
     * 实际 varint 长度与预留不一致时（UTF-8 实际长度比「每字 3 字节」小，例如
     * 「绝大多数是 ASCII + 少量中文」的串），把内容<b>左移</b>补齐再写前缀。
     * 旧实现是「先写内容、再把内容整块右移补前缀」，右移每次都发生；
     * 现在右移只在罕见分支发生 ⇒ 主流路径（中文串）完全省掉了这次整块 memmove。</p>
     */
    private int writeStringLenPrefix(int prefixPos, int reserve, int contentLen) {
        byte[] _bs = this.bs;
        int vn = varintLen(contentLen);
        if (vn != reserve) {
            System.arraycopy(_bs, prefixPos + reserve, _bs, prefixPos + vn, contentLen);
        }
        writeVarint(_bs, prefixPos, contentLen);
        return vn + contentLen;
    }

    /**
     * 嵌套消息的长度前缀<b>预留字节数</b>。
     *
     * <p>嵌套消息的载荷大小事前不可知，但绝大多数落在 <b>128..16383</b> 字节
     * （对应 2 字节的 varint 前缀）⇒ 预留 2 字节后，实际前缀长度与预留一致
     * ⇒ <b>整块内容一次都不用搬</b>。
     * 旧实现一律只预留 1 字节 ⇒ 只要载荷 ≥128 字节，就必然把内容整块右移一次
     * （右移比拷贝还贵 1.4 倍，实测占嵌套消息编码时间的 1/3）。</p>
     */
    protected static final int NESTED_LEN_RESERVE = 2;

    /**
     * 回填长度前缀（varint），返回「前缀 + 内容」的总字节数。
     *
     * <p>内容已经写在 {@code prefixPos + reserve} 处（{@code reserve} 是预留的前缀字节数）。
     * 实际 varint 长度与预留不一致时，把内容按差值平移：
     * <ul>
     *   <li>实际更短 ⇒ 向左平移（不用扩容）；</li>
     *   <li>实际更长 ⇒ 向右平移（要先扩容，此时 {@code pos} 是内容的末尾）。</li>
     * </ul>
     * 差值为 0 时<b>一次都不搬</b>。</p>
     *
     * <p>与 {@link #writeLenMoveBytes} 的区别：后者固定只预留 1 字节，
     * 写完内容后按实际前缀长度把内容整块<b>右移</b>（L−1 字节）⇒ 载荷 ≥128 字节时必然搬一次。</p>
     */
    protected int writeLenPrefixReserve(int prefixPos, int reserve, int contentLen) {
        byte[] _bs = this.bs;
        int vn = varintLen(contentLen);
        if (vn != reserve) {
            if (vn > reserve) {
                // 向右平移需要额外空间（此时 pos 是内容的末尾）
                expand(vn - reserve);
                _bs = this.bs;
            }
            System.arraycopy(_bs, prefixPos + reserve, _bs, prefixPos + vn, contentLen);
        }
        writeVarint(_bs, prefixPos, contentLen);
        return vn + contentLen;
    }

    /**
     * varint 编码后的字节数。
     */
    protected static int varintLen(int value) {
        if ((value & ~0x7F) == 0) {
            return 1;
        }
        if ((value & ~0x3FFF) == 0) {
            return 2;
        }
        if ((value & ~0x1FFFFF) == 0) {
            return 3;
        }
        if ((value & ~0x0FFFFFFF) == 0) {
            return 4;
        }
        return 5;
    }

    /**
     * 3~5 字节 varint（{@code value ≥ 16384}）—— <b>packed 路径的冷分支</b>。
     *
     * <p>★ <b>为什么 1~2 字节的 varint 不在这里写</b>：
     * packed int 的实际取值绝大多数落在 0~16383（实测负载 0~999 与 0~15999 ⇒
     * 1~2 字节占 <b>99.2%</b>），所以 <b>1 字节与 2 字节两种写法被直接展开在
     * {@code StandardProtoBufWriter} 的 packed 循环里</b>（<b>一次方法调用都没有</b>），
     * 只有 ≥3 字节才回到本方法。</p>
     *
     * <p>★★ <b>为什么必须「零调用」而不是「拆成小方法」</b> ——
     * HotSpot 的内联阈值 {@code MaxInlineSize=35}（字节码）<b>是随内联深度递减的</b>：
     * 深度 1 实测 ≤36 字节码恒内联，深度 4 就掉到 ≤22、深度 5 掉到 ≤19；
     * 超过阈值的方法变成「<b>热才内联</b>」⇒ <b>编译时机不同 ⇒ 性能随机落在两个档</b>。
     * 实测（同一个 {@code ProtoCodecBenchmark}，5 次启动各落一边，google 对照列稳定）：</p>
     * <ul>
     *   <li>5 档 varint 写成一个展开方法（164 字节码）⇒ <b>1189ns / 1640ns 双峰</b>；</li>
     *   <li>拆成「分发 21/21/24 + 写入 9/22/32/34」的一串小方法 ⇒ <b>1267ns / 1608ns 双峰</b>
     *       （当时的 2 字节写入方法只有 22 字节码，落在深度 3~4 的阈值边缘 ⇒ 概率性内联；
     *        该方法后来被删掉了，2 字节改为直接展开在 packed 循环里）；</li>
     *   <li><b>1~2 字节直接展开在循环里（零调用）⇒ 1196~1212ns，稳定</b>。</li>
     * </ul>
     * ⇒ 结论：<b>热路径要么零调用，要么保证「≤35 字节码 + 调用深度足够浅」；
     * 拆方法本身不能消除双峰</b>。</p>
     *
     * <p>★ 另一条纪律：热路径上的 varint 写入<b>不能写成 while 循环</b> ——
     * 循环版实测每元素多 ~0.3 ns（循环回边）⇒ packed int 编码回退 15%~24%。</p>
     */
    protected static int writeVarintAt3to5(byte[] bs, int p, int value) {
        if ((value >>> 21) == 0) {
            writeVarint3(bs, p, value);
            return p + 3;
        }
        return writeVarintAtWide(bs, p, value);
    }

    /**
     * 3 字节 varint（16384~2097151）。
     *
     * <p>★ {@code value | -128} 与 {@code value | 0x80} 的<b>低 8 位完全相同</b>
     * （{@code -128 == 0xFFFFFF80}，被 {@code (byte)} 截断后只剩 {@code 0x80}），
     * 但前者是 {@code bipush}（2 字节码）、后者是 {@code sipush}（3 字节码）
     * ⇒ 每个字节省 1 字节码 ⇒ 整个方法从 37 降到 32。</p>
     */
    protected static void writeVarint3(byte[] bs, int p, int value) {
        bs[p] = (byte) (value | -128);
        bs[p + 1] = (byte) ((value >>> 7) | -128);
        bs[p + 2] = (byte) (value >>> 14);
    }

    /**
     * 4~5 字节 varint（≥2097152，罕见）。
     *
     * <p>★ 写成 <b>do-while</b>（而不是「先判后写」的 while）：调用方保证 {@code value ≥ 2^21}
     * ⇒ 第一个字节一定要带 0x80 ⇒ 可以先写再判 ⇒ 少一段判断分支 ⇒ 34 字节码 ⇒ 永远内联。</p>
     */
    protected static int writeVarintAtWide(byte[] bs, int p, int value) {
        int v = value;
        do {
            bs[p++] = (byte) (v | -128);
            v >>>= 7;
        } while ((v >>> 7) != 0);
        bs[p] = (byte) v;
        return p + 1;
    }

    /**
     * 在 {@code bs[p]} 处写 {@code value} 的 varint（64 位，负数符号扩展），返回写完后的下标。
     *
     * <p>字节与 {@link #writeUInt64_0} 完全一致。</p>
     *
     * <p>★ 拆成「1 字节快路径 + 宽路径」两个方法：{@code value} 落在 0~127 时只写 1 字节
     * （{@link #writeVarint64At} 27 字节码 ⇒ 永远内联），
     * 否则走 {@link #writeVarint64AtWide}（44 字节码 ⇒ 「热才内联」，
     * 但这条路径只在<b>packed int32 里出现负数</b>时才走到，罕见 ⇒ 不影响热路径的确定性）。</p>
     */
    protected static int writeVarint64At(byte[] bs, int p, long value) {
        if ((value & ~0x7FL) == 0) {
            bs[p] = (byte) value;
            return p + 1;
        }
        return writeVarint64AtWide(bs, p, value);
    }

    /**
     * 2~10 字节 varint（64 位）：do-while 结构，调用方保证 {@code value} 需要 ≥2 字节。
     */
    protected static int writeVarint64AtWide(byte[] bs, int p, long value) {
        long v = value;
        do {
            bs[p++] = (byte) ((int) v | -128);
            v >>>= 7;
        } while ((v >>> 7) != 0);
        bs[p] = (byte) v;
        return p + 1;
    }

    /**
     * 在 {@code p} 处写入 {@code value} 的 varint（不移动 {@code pos}），返回写入的字节数。
     */
    protected static int writeVarint(byte[] bs, int p, int value) {
        int v = value;
        if ((v & ~0x7F) == 0) {
            bs[p] = (byte) v;
            return 1;
        }
        bs[p++] = (byte) ((v & 0x7F) | 0x80);
        v >>>= 7;
        if ((v & ~0x7F) == 0) {
            bs[p] = (byte) v;
            return 2;
        }
        bs[p++] = (byte) ((v & 0x7F) | 0x80);
        v >>>= 7;
        if ((v & ~0x7F) == 0) {
            bs[p] = (byte) v;
            return 3;
        }
        bs[p++] = (byte) ((v & 0x7F) | 0x80);
        v >>>= 7;
        if ((v & ~0x7F) == 0) {
            bs[p] = (byte) v;
            return 4;
        }
        bs[p++] = (byte) ((v & 0x7F) | 0x80);
        v >>>= 7;
        bs[p] = (byte) v;
        return 5;
    }

    protected final int writeChars(final String value, int start, int end, int pos) {
        String v = value;
//        int p = pos;
//        byte[] _bs = this.bs;
        FastBuf buf = LOCAL_FAST_BUF.get();
        buf.clear();
        if (buf.remain() < v.length() * 3) {
            FastBuf bufNew = new FastBuf(v.length() * 3);
            buf = bufNew;
            LOCAL_FAST_BUF.set(buf);
        }

        for (int i=start;i<end;i++) {
            char c = v.charAt(i);
            if ((c & ~0x7F) == 0) {
                //_bs[p++] = (byte) c;
                buf.writeNotCheck((byte) c);
            } else if (c < 0x800) {
//                _bs[p++] = (byte) ((0xF << 6) | (c >> 6));
//                _bs[p++] = (byte) ( 0x80 | (0x3F & c));
                buf.writeNotCheck((byte) ((0xF << 6) | (c >>> 6)));
                buf.writeNotCheck((byte) ( 0x80 | (0x3F & c)));
            } else if (Character.isHighSurrogate(c)) {
                int codePoint = Character.toCodePoint((char) c, (char) value.charAt(i+1));
//                _bs[p++] = (byte) (0xF0 | ((codePoint >> 18) & 0x07));
//                _bs[p++] = (byte) (0x80 | ((codePoint >> 12) & 0x3F));
//                _bs[p++] = (byte) (0x80 | ((codePoint >>  6) & 0x3F));
//                _bs[p++] = (byte) (0x80 | ( codePoint        & 0x3F));
                buf.writeNotCheck((byte) (0xF0 | ((codePoint >> 18) & 0x07)));
                buf.writeNotCheck((byte) (0x80 | ((codePoint >> 12) & 0x3F)));
                buf.writeNotCheck((byte) (0x80 | ((codePoint >>  6) & 0x3F)));
                buf.writeNotCheck((byte) (0x80 | ( codePoint        & 0x3F)));
                i++;
            } else {
//                _bs[p++] = (byte) ((0xF << 5) | (c >>> 12));
//                _bs[p++] = (byte) (0x80 | (0x3F & (c >>> 6)));
//                _bs[p++] = (byte) (0x80 | (0x3F & c));
                buf.writeNotCheck((byte) ((0xF << 5) | (c >>> 12)));
                buf.writeNotCheck((byte) (0x80 | (0x3F & (c >>> 6))));
                buf.writeNotCheck((byte) (0x80 | (0x3F & c)));
            }
        }
        int len = (int)(buf.wpos() - buf.address());
        buf.writeTo(bs, pos, len);
        return len;
    }

    protected final int writeChars( final char[] value, int start, int end, int pos){
        byte[] _bs = this.bs;
        // ★ 字节偏移用 long 计数：逐字节编码时偏移是连续递增的，
        //   int 计数每写一个字节都要符号扩展成 long（i2l），拉长「偏移 → 地址」的依赖链。
        //   实测中文 16KB 串 16153ns → 12750ns（探针：edap-grpc-benchmark/StringEncodeCandidates）。
        long p = pos;
        int i = start;
        for (;i < end; i++) {
            char c = value[i];
            if (c < 128) {
                UnsafeUtil.writeByte(_bs, p++, (byte) c);
            } else if (c < 0x800) {
                UnsafeUtil.writeByte(_bs, p++, (byte) ((0xF << 6) | (c >>> 6)));
                UnsafeUtil.writeByte(_bs, p++, (byte) (0x80 | (0x3F & c)));
            } else if (c >= '\ud800' && c <= '\udfff') {
                int codePoint = Character.toCodePoint((char) c, (char) value[i + 1]);
                UnsafeUtil.writeByte(_bs, p++, (byte) (0xF0 | ((codePoint >> 18) & 0x07)));
                UnsafeUtil.writeByte(_bs, p++, (byte) (0x80 | ((codePoint >> 12) & 0x3F)));
                UnsafeUtil.writeByte(_bs, p++, (byte) (0x80 | ((codePoint >> 6) & 0x3F)));
                UnsafeUtil.writeByte(_bs, p++, (byte) (0x80 | (codePoint & 0x3F)));
                i++;
            } else {
                UnsafeUtil.writeByte(_bs, p++, (byte) ((0xF << 5) | (c >>> 12)));
                UnsafeUtil.writeByte(_bs, p++, (byte) (0x80 | (0x3F & (c >>> 6))));
                UnsafeUtil.writeByte(_bs, p++, (byte) (0x80 | (0x3F & c)));
            }
        }
        return (int) (p - pos);
    }

    @Override
    public void writeBytes(final byte[] fieldData, final byte[] value) {
        if (value == null || value.length == 0) {
            return;
        }
        writeByteArray(fieldData, value, 0, value.length);
    }

    @Override
    public void writeBytes(final byte[] fieldData, final Byte[] value) {
        if (value == null) {
            return;
        }
        int length = value.length;
        expand(length + MAX_VARINT_SIZE * 2);
        writeFieldData(fieldData);
        writeUInt32_0(length);

        int p = pos;
        byte[] _bs = this.bs;
        for (int i=0;i<length;i++) {
            _bs[p++] = value[i].byteValue();
        }
        pos = p;
    }

    @Override
    public void writeBytes(byte[] value) {
        if (value == null || value.length == 0) {
            return;
        }
        int len = value.length;
        expand(len);
        System.arraycopy(value, 0, bs, pos, len);
        pos += len;
    }

    @Override
    public void writeByteArray(final byte[] fieldData, final byte[] value,
                                     int offset, int length) {
        expand(length + MAX_VARINT_SIZE * 2);
        writeFieldData(fieldData);
        writeUInt32_0(length);
        writeByteArray_0(value, offset, length);
    }

    @Override
    public void writeByteArray(final byte[] value, int offset, int length) {
        expand(length + 5);
        writeUInt32_0(length);
        System.arraycopy(value, offset, bs, pos, length);
        pos += length;
    }

    protected void writeByteArray_0(final byte[] value, int offset, int length) {
        System.arraycopy(value, offset, bs, pos, length);
        pos += length;
    }

    protected int writeLenMoveBytes(byte[] bs, int p, int len) {
        if ((len & ~0x7F) == 0) {
            bs[p] = (byte) len;
            return 0;
        } else {
            byte[] _bs = bs;
            int value = len;
            _bs[p++] = (byte) ((value & 0x7F) | 0x80);
            value >>>= 7;
            if ((value & ~0x7F) == 0) {
                expand(1);
                _bs = this.bs;
                System.arraycopy(_bs, p, _bs, p+1, len);
                _bs[p++] = (byte) value;
                return 1;
            } else {
                byte b2 = (byte) ((value & 0x7F) | 0x80);
                value >>>= 7;
                if ((value & ~0x7F) == 0) {
                    expand(2);
                    _bs = this.bs;
                    System.arraycopy(_bs, p, _bs, p+2, len);
                    _bs[p++] = b2;
                    _bs[p++] = (byte) value;
                    return 2;
                } else {
                    byte b3 = (byte) ((value & 0x7F) | 0x80);
                    value >>>= 7;
                    if ((value & ~0x7F) == 0) {
                        expand(3);
                        _bs = this.bs;
                        System.arraycopy(_bs, p, _bs, p+3, len);
                        _bs[p++] = b2;
                        _bs[p++] = b3;
                        _bs[p++] = (byte) value;
                        return 3;
                    } else {
                        byte b4 = (byte) ((value & 0x7F) | 0x80);
                        value >>>= 7;
                        if ((value & ~0x7F) == 0) {
                            expand(4);
                            _bs = this.bs;
                            System.arraycopy(_bs, p, _bs, p+4, len);
                            _bs[p++] = b2;
                            _bs[p++] = b3;
                            _bs[p++] = b4;
                            _bs[p++] = (byte) value;
                            return 4;
                        }
                    }
                }
            }
        }
        return 0;
    }

    /**
     * 不检查空间是否够用直接写uint32数据
     * @param value
     */
    protected void writeUInt32_0(int value) {
        /**/
        byte[] _bs = this.bs;
        int p = pos;
        if ((value & ~0x7F) == 0) {
            _bs[p++] = (byte) value;
        } else {
            _bs[p++] = (byte) ((value & 0x7F) | 0x80);
            value >>>= 7;
            if ((value & ~0x7F) == 0) {
                _bs[p++] = (byte) value;
            } else {
                _bs[p++] = (byte) ((value & 0x7F) | 0x80);
                value >>>= 7;
                if ((value & ~0x7F) == 0) {
                    _bs[p++] = (byte) value;
                } else {
                    _bs[p++] = (byte) ((value & 0x7F) | 0x80);
                    value >>>= 7;
                    if ((value & ~0x7F) == 0) {
                        _bs[p++] = (byte) value;
                    } else {
                        _bs[p++] = (byte) ((value & 0x7F) | 0x80);
                        value >>>= 7;
                        if ((value & ~0x7F) == 0) {
                            _bs[p++] = (byte) value;
                        }
                    }
                }
            }
        }
        pos = p;
        /**/
        /*
        byte[] bs = this.bs;
        int start = pos;
        while (true) {
            if ((value & ~0x7F) == 0) {
                bs[start++] = (byte) value;
                pos = start;
                return;
            } else {
                bs[start++] = (byte) ((value & 0x7F) | 0x80);
                value >>>= 7;
            }
        }
        */
    }

    @Override
    public void writeUInt64(long value) {
        expand(MAX_VARLONG_SIZE);
        byte[] _bs  = bs;
        int    _pos = pos;
        while (true) {
            if ((value & ~0x7FL) == 0) {
                _bs[_pos++] = (byte) value;
                pos = _pos;
                return;
            } else {
                _bs[_pos++] = ((byte) (((int) value & 0x7F) | 0x80));
                value >>>= 7;
            }
        }

    }

    protected void writeUInt64_0(long value) {
        byte[] _bs  = bs;
        int    _pos = pos;
        while (true) {
            if ((value & ~0x7FL) == 0) {
                _bs[_pos++] = (byte) value;
                pos = _pos;
                return;
            } else {
                _bs[_pos++] = ((byte) (((int) value & 0x7F) | 0x80));
                value >>>= 7;
            }
        }
    }

    @Override
    public void writeFixed32(int value) {
        expand(FIXED_32_SIZE);
        byte[] _bs  = bs;
        int    _pos = pos;
        _bs[_pos++] = (byte) ((int) (value      ) & 0xFF);
        _bs[_pos++] = (byte) ((int) (value >>  8) & 0xFF);
        _bs[_pos++] = (byte) ((int) (value >> 16) & 0xFF);
        _bs[_pos++] = (byte) ((int) (value >> 24) & 0xFF);
        pos = _pos;
    }

    protected void writeFixed32_0(int value) {
        byte[] _bs  = bs;
        int    _pos = pos;
        _bs[_pos++] = (byte) ((int) (value      ) & 0xFF);
        _bs[_pos++] = (byte) ((int) (value >>  8) & 0xFF);
        _bs[_pos++] = (byte) ((int) (value >> 16) & 0xFF);
        _bs[_pos++] = (byte) ((int) (value >> 24) & 0xFF);
        pos = _pos;
    }

    @Override
    public void writeFixed64(long value) {
        expand(FIXED_64_SIZE);
        byte[] _bs  = bs;
        int    _pos = pos;
        _bs[_pos++] = ((byte) ((int) (value      ) & 0xFF));
        _bs[_pos++] = ((byte) ((int) (value >>  8) & 0xFF));
        _bs[_pos++] = ((byte) ((int) (value >> 16) & 0xFF));
        _bs[_pos++] = ((byte) ((int) (value >> 24) & 0xFF));
        _bs[_pos++] = ((byte) ((int) (value >> 32) & 0xFF));
        _bs[_pos++] = ((byte) ((int) (value >> 40) & 0xFF));
        _bs[_pos++] = ((byte) ((int) (value >> 48) & 0xFF));
        _bs[_pos++] = ((byte) ((int) (value >> 56) & 0xFF));
        pos = _pos;
    }

    protected void writeFixed64_0(long value) {
        byte[] _bs  = bs;
        int    _pos = pos;
        _bs[_pos++] = ((byte) ((int) (value      ) & 0xFF));
        _bs[_pos++] = ((byte) ((int) (value >>  8) & 0xFF));
        _bs[_pos++] = ((byte) ((int) (value >> 16) & 0xFF));
        _bs[_pos++] = ((byte) ((int) (value >> 24) & 0xFF));
        _bs[_pos++] = ((byte) ((int) (value >> 32) & 0xFF));
        _bs[_pos++] = ((byte) ((int) (value >> 40) & 0xFF));
        _bs[_pos++] = ((byte) ((int) (value >> 48) & 0xFF));
        _bs[_pos++] = ((byte) ((int) (value >> 56) & 0xFF));
        pos = _pos;
    }

    @Override
    public void expand(int minLength) {
        if (bs.length - pos <= minLength) {
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
                System.arraycopy(bs, 0, res, 0, pos);

                wbuf.bs = res;
                wbuf.out.setLocalBytes(res);
                bs = res;
            }
        }
    }

    public void reset() {
        pos = 0;
        wpos = 0;
    }

    @Override
    public void setWPos(int wpos) {
        this.wpos = 0;
    }

    /**
     * 写入FastBuf的下标
     */
    private int wpos = 0;
    @Override
    public int toFastBuf(FastBuf buf) {
        int len = buf.write(bs, wpos, pos);
        wpos += len;
        return len;
    }
}