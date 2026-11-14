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

package io.edap.protobuf.writer;

import io.edap.io.BufOut;
import io.edap.protobuf.EncodeException;
import io.edap.protobuf.MapEntryEncoder;
import io.edap.protobuf.ProtoBufEncoder;
import io.edap.protobuf.ext.AnyCodec;
import io.edap.protobuf.model.ProtoBufOption;
import io.edap.protobuf.util.ProtoUtil;
import io.edap.protobuf.wire.Field;
import io.edap.util.CollectionUtils;

import java.util.*;

import static io.edap.protobuf.ProtoBufWriter.encodeZigZag32;
import static io.edap.protobuf.ProtoBufWriter.encodeZigZag64;
import static io.edap.protobuf.util.ProtoUtil.*;
import static io.edap.protobuf.wire.WireFormat.MAX_VARINT_SIZE;
import static io.edap.protobuf.wire.WireFormat.MAX_VARLONG_SIZE;
import static io.edap.util.CollectionUtils.isEmpty;

public class StandardProtoBufWriter extends AbstractWriter {

    public StandardProtoBufWriter(BufOut out) {
        super(out);
    }

    /**
     * packed 字段的公共写法：<b>单遍</b>写内容 + 写完回填长度前缀。
     *
     * <p>结构：长度前缀先<b>预留 {@code reserve} 字节</b>，内容从 {@code oldPos + reserve} 开始
     * 用<b>局部下标</b>连续写（不读写 {@code pos} 字段 ⇒ 每个元素省一次字段读 + 一次字段写），
     * 写完算出实际长度 {@code contentLen}，再由
     * {@link #writeLenPrefixReserve(int, int, int)} 把前缀补上（长度不一致时整块平移，
     * 长度一致时<b>一次都不搬</b>）。</p>
     *
     * <p>★ 为什么不用 protobuf-java 的「两遍法（先算长度 → 写长度 → 写内容）」：
     * 两遍法要多扫一遍载荷（装箱 {@code List<Integer>} 上实测 <b>≈1.3 ns/元素</b>），
     * 而省下的只是「按差值平移」——实测在常见负载上<b>差值为 0，一次都不搬</b>
     * ⇒ 两遍法是<b>纯亏</b>（packed int32 实测 +67%~+83%）。详见
     * {@code edap-grpc-parent/edap-grpc-benchmark/doc/bench/proto-codec-20261113-string.md} 第二十八~三十节。</p>
     *
     * <p>★ 每元素的字节数上界：<b>uint32 / int32 正数</b> 都按无符号 32 位 varint 写 ⇒ 最多
     * <b>5 字节</b>（{@link #MAX_VARINT_SIZE}）；只有 <b>int32 的负数</b>要符号扩展成 64 位 varint
     * ⇒ 最多 <b>10 字节</b>（{@link #MAX_VARLONG_SIZE}）。
     * 旧实现一律按 5 字节预留 ⇒ <b>int32 元素里有负数时缓冲会越界</b>
     * （实测 {@code ArrayIndexOutOfBoundsException}）。
     * ⇒ 这里仍然按 <b>5 字节/元素</b>预留（与旧实现同，不多占内存），
     * <b>只在「负数」这条冷分支上</b>检查容量（实测：每个元素都查 ⇒ 慢 15%~50%；
     * 只在负数上查 ⇒ 常见负载零成本）。</p>
     */
    /**
     * 开始写一个 packed 整型字段：写 tag、预留长度前缀，返回<b>长度前缀</b>的位置。
     *
     * @param reserve        长度前缀预留的字节数
     * @param contentReserve 内容的预留字节数（按每元素 {@link #MAX_VARINT_SIZE} 估）
     */
    private int writePackedIntStart(byte[] fieldData, int reserve, int contentReserve) {
        expand((MAX_VARINT_SIZE << 1) + contentReserve + reserve);
        writeFieldData(fieldData);
        return pos;
    }

    /**
     * 结束写一个 packed 字段：按实际内容长度回填长度前缀，并推进 {@code pos}。
     */
    private void writePackedIntEnd(int oldPos, int reserve, int contentEnd) {
        int contentLen = contentEnd - oldPos - reserve;
        // ★ expand() 用 pos 判断剩余容量，必须先同步成「内容末尾」再回填前缀
        pos = contentEnd;
        pos = oldPos + writeLenPrefixReserve(oldPos, reserve, contentLen);
    }

    @Override
    public void writePackedInts(byte[] fieldData, int[] values, Field.Type type) {
        if (values == null || values.length == 0) {
            return;
        }
        int size = values.length;
        switch (type) {
            case INT32: {
                // ★ 单遍写法（见 writePackedIntStart 的说明）
                // int32 的负数要符号扩展成 64 位 varint ⇒ 最多 10 字节
                int reserve = varintLen(size * MAX_VARINT_SIZE);
                int oldPos = writePackedIntStart(fieldData, reserve, size * MAX_VARINT_SIZE);
                byte[] _bs = bs;
                int cap = _bs.length;
                int p = oldPos + reserve;
                for (int i = 0; i < size; i++) {
                    int v = values[i];
                    if (v >= 0) {
                        // ≤5 字节 ⇒ 预留已够
                        if ((v >>> 14) == 0) {
                            if ((v >>> 7) == 0) {
                                _bs[p++] = (byte) v;
                            } else {
                                _bs[p++] = (byte) (v | -128);
                                _bs[p++] = (byte) (v >>> 7);
                            }
                        } else {
                            p = writeVarintAt3to5(_bs, p, v);
                        }
                    } else {
                        // ★ int32 负数 ⇒ 10 字节 ⇒ 这里才查容量
                        if (p + MAX_VARLONG_SIZE > cap) {
                            pos = p;
                            expand(MAX_VARLONG_SIZE);
                            _bs = bs;
                            cap = _bs.length;
                        }
                        p = writeVarint64At(_bs, p, v);
                    }
                }
                writePackedIntEnd(oldPos, reserve, p);
                return;
            }
            case UINT32: {
                // uint32 是无符号语义（-1 ⇒ FF FF FF FF 0F）⇒ 永远 ≤5 字节 ⇒ 不需要检查容量
                int reserve = varintLen(size * MAX_VARINT_SIZE);
                int oldPos = writePackedIntStart(fieldData, reserve, size * MAX_VARINT_SIZE);
                byte[] _bs = bs;
                int p = oldPos + reserve;
                for (int i = 0; i < size; i++) {
                    int v = values[i];
                    if ((v >>> 14) == 0) {
                        if ((v >>> 7) == 0) {
                            _bs[p++] = (byte) v;
                        } else {
                            _bs[p++] = (byte) (v | -128);
                            _bs[p++] = (byte) (v >>> 7);
                        }
                    } else {
                        p = writeVarintAt3to5(_bs, p, v);
                    }
                }
                writePackedIntEnd(oldPos, reserve, p);
                return;
            }
            case SINT32: {
                // zigzag 后的 int32 最多 5 字节 ⇒ 预留已够，不需要检查容量
                int reserve = varintLen(size * MAX_VARINT_SIZE);
                int oldPos = writePackedIntStart(fieldData, reserve, size * MAX_VARINT_SIZE);
                byte[] _bs = bs;
                int p = oldPos + reserve;
                for (int i = 0; i < size; i++) {
                    int v = encodeZigZag32(values[i]);
                    if ((v >>> 14) == 0) {
                        if ((v >>> 7) == 0) {
                            _bs[p++] = (byte) v;
                        } else {
                            _bs[p++] = (byte) (v | -128);
                            _bs[p++] = (byte) (v >>> 7);
                        }
                    } else {
                        p = writeVarintAt3to5(_bs, p, v);
                    }
                }
                writePackedIntEnd(oldPos, reserve, p);
                return;
            }
            case FIXED32:
            case SFIXED32:
                expand((MAX_VARINT_SIZE << 1) + (size << 2));
                writeFieldData(fieldData);
                writeUInt32_0(size << 2);
                for (int i = 0; i < size; i++) {
                    writeFixed32_0(values[i]);
                }
                return;
            default:
                break;
        }
    }

    @Override
    public void writePackedInts(byte[] fieldData, Integer[] values, Field.Type type) {
        if (values == null || values.length == 0) {
            return;
        }
        int size = values.length;
        switch (type) {
            case INT32: {
                // ★ 单遍写法（见 writePackedIntStart 的说明）
                // int32 的负数要符号扩展成 64 位 varint ⇒ 最多 10 字节
                int reserve = varintLen(size * MAX_VARINT_SIZE);
                int oldPos = writePackedIntStart(fieldData, reserve, size * MAX_VARINT_SIZE);
                byte[] _bs = bs;
                int cap = _bs.length;
                int p = oldPos + reserve;
                for (int i = 0; i < size; i++) {
                    int v = values[i].intValue();
                    if (v >= 0) {
                        // ≤5 字节 ⇒ 预留已够
                        if ((v >>> 14) == 0) {
                            if ((v >>> 7) == 0) {
                                _bs[p++] = (byte) v;
                            } else {
                                _bs[p++] = (byte) (v | -128);
                                _bs[p++] = (byte) (v >>> 7);
                            }
                        } else {
                            p = writeVarintAt3to5(_bs, p, v);
                        }
                    } else {
                        // ★ int32 负数 ⇒ 10 字节 ⇒ 这里才查容量
                        if (p + MAX_VARLONG_SIZE > cap) {
                            pos = p;
                            expand(MAX_VARLONG_SIZE);
                            _bs = bs;
                            cap = _bs.length;
                        }
                        p = writeVarint64At(_bs, p, v);
                    }
                }
                writePackedIntEnd(oldPos, reserve, p);
                return;
            }
            case UINT32: {
                // uint32 是无符号语义（-1 ⇒ FF FF FF FF 0F）⇒ 永远 ≤5 字节 ⇒ 不需要检查容量
                int reserve = varintLen(size * MAX_VARINT_SIZE);
                int oldPos = writePackedIntStart(fieldData, reserve, size * MAX_VARINT_SIZE);
                byte[] _bs = bs;
                int p = oldPos + reserve;
                for (int i = 0; i < size; i++) {
                    int v = values[i].intValue();
                    if ((v >>> 14) == 0) {
                        if ((v >>> 7) == 0) {
                            _bs[p++] = (byte) v;
                        } else {
                            _bs[p++] = (byte) (v | -128);
                            _bs[p++] = (byte) (v >>> 7);
                        }
                    } else {
                        p = writeVarintAt3to5(_bs, p, v);
                    }
                }
                writePackedIntEnd(oldPos, reserve, p);
                return;
            }
            case SINT32: {
                // zigzag 后的 int32 最多 5 字节 ⇒ 预留已够，不需要检查容量
                int reserve = varintLen(size * MAX_VARINT_SIZE);
                int oldPos = writePackedIntStart(fieldData, reserve, size * MAX_VARINT_SIZE);
                byte[] _bs = bs;
                int p = oldPos + reserve;
                for (int i = 0; i < size; i++) {
                    int v = encodeZigZag32(values[i].intValue());
                    if ((v >>> 14) == 0) {
                        if ((v >>> 7) == 0) {
                            _bs[p++] = (byte) v;
                        } else {
                            _bs[p++] = (byte) (v | -128);
                            _bs[p++] = (byte) (v >>> 7);
                        }
                    } else {
                        p = writeVarintAt3to5(_bs, p, v);
                    }
                }
                writePackedIntEnd(oldPos, reserve, p);
                return;
            }
            case FIXED32:
            case SFIXED32:
                expand((MAX_VARINT_SIZE << 1) + (size << 2));
                writeFieldData(fieldData);
                writeUInt32_0(size << 2);
                for (int i = 0; i < size; i++) {
                    writeFixed32_0(values[i]);
                }
                return;
            default:
                break;
        }
    }

    @Override
    public void writePackedInts(byte[] fieldData, List<Integer> values, Field.Type type) {
        if (isEmpty(values)) {
            return;
        }
        int size = values.size();
        switch (type) {
            case INT32: {
                // ★ 单遍写法（见 writePackedIntStart 的说明）
                // int32 的负数要符号扩展成 64 位 varint ⇒ 最多 10 字节
                int reserve = varintLen(size * MAX_VARINT_SIZE);
                int oldPos = writePackedIntStart(fieldData, reserve, size * MAX_VARINT_SIZE);
                byte[] _bs = bs;
                int cap = _bs.length;
                int p = oldPos + reserve;
                for (int i = 0; i < size; i++) {
                    int v = values.get(i).intValue();
                    if (v >= 0) {
                        // ≤5 字节 ⇒ 预留已够
                        if ((v >>> 14) == 0) {
                            if ((v >>> 7) == 0) {
                                _bs[p++] = (byte) v;
                            } else {
                                _bs[p++] = (byte) (v | -128);
                                _bs[p++] = (byte) (v >>> 7);
                            }
                        } else {
                            p = writeVarintAt3to5(_bs, p, v);
                        }
                    } else {
                        // ★ int32 负数 ⇒ 10 字节 ⇒ 这里才查容量
                        if (p + MAX_VARLONG_SIZE > cap) {
                            pos = p;
                            expand(MAX_VARLONG_SIZE);
                            _bs = bs;
                            cap = _bs.length;
                        }
                        p = writeVarint64At(_bs, p, v);
                    }
                }
                writePackedIntEnd(oldPos, reserve, p);
                return;
            }
            case UINT32: {
                // uint32 是无符号语义（-1 ⇒ FF FF FF FF 0F）⇒ 永远 ≤5 字节 ⇒ 不需要检查容量
                int reserve = varintLen(size * MAX_VARINT_SIZE);
                int oldPos = writePackedIntStart(fieldData, reserve, size * MAX_VARINT_SIZE);
                byte[] _bs = bs;
                int p = oldPos + reserve;
                for (int i = 0; i < size; i++) {
                    int v = values.get(i).intValue();
                    if ((v >>> 14) == 0) {
                        if ((v >>> 7) == 0) {
                            _bs[p++] = (byte) v;
                        } else {
                            _bs[p++] = (byte) (v | -128);
                            _bs[p++] = (byte) (v >>> 7);
                        }
                    } else {
                        p = writeVarintAt3to5(_bs, p, v);
                    }
                }
                writePackedIntEnd(oldPos, reserve, p);
                return;
            }
            case SINT32: {
                // zigzag 后的 int32 最多 5 字节 ⇒ 预留已够，不需要检查容量
                int reserve = varintLen(size * MAX_VARINT_SIZE);
                int oldPos = writePackedIntStart(fieldData, reserve, size * MAX_VARINT_SIZE);
                byte[] _bs = bs;
                int p = oldPos + reserve;
                for (int i = 0; i < size; i++) {
                    int v = encodeZigZag32(values.get(i).intValue());
                    if ((v >>> 14) == 0) {
                        if ((v >>> 7) == 0) {
                            _bs[p++] = (byte) v;
                        } else {
                            _bs[p++] = (byte) (v | -128);
                            _bs[p++] = (byte) (v >>> 7);
                        }
                    } else {
                        p = writeVarintAt3to5(_bs, p, v);
                    }
                }
                writePackedIntEnd(oldPos, reserve, p);
                return;
            }
            case FIXED32:
            case SFIXED32:
                expand((MAX_VARINT_SIZE << 1) + (size << 2));
                writeFieldData(fieldData);
                writeUInt32_0(size << 2);
                for (Integer v : values) {
                    writeFixed32_0(v);
                }
                return;
            default:
                break;
        }
    }

    @Override
    public void writePackedInts(byte[] fieldData, Iterable<Integer> values, Field.Type type) {
        if (isEmpty(values)) {
            return;
        }
        int size = 0;
        Iterator<Integer> itr = values.iterator();
        while (itr.hasNext()) {
            itr.next();
            size++;
        }
        switch (type) {
            case INT32: {
                // ★ 单遍写法（见 writePackedIntStart 的说明）
                // int32 的负数要符号扩展成 64 位 varint ⇒ 最多 10 字节
                int reserve = varintLen(size * MAX_VARINT_SIZE);
                int oldPos = writePackedIntStart(fieldData, reserve, size * MAX_VARINT_SIZE);
                byte[] _bs = bs;
                int cap = _bs.length;
                int p = oldPos + reserve;
                Iterator<Integer> it = values.iterator();
                while (it.hasNext()) {
                    int v = it.next().intValue();
                    if (v >= 0) {
                        // ≤5 字节 ⇒ 预留已够
                        if ((v >>> 14) == 0) {
                            if ((v >>> 7) == 0) {
                                _bs[p++] = (byte) v;
                            } else {
                                _bs[p++] = (byte) (v | -128);
                                _bs[p++] = (byte) (v >>> 7);
                            }
                        } else {
                            p = writeVarintAt3to5(_bs, p, v);
                        }
                    } else {
                        // ★ int32 负数 ⇒ 10 字节 ⇒ 这里才查容量
                        if (p + MAX_VARLONG_SIZE > cap) {
                            pos = p;
                            expand(MAX_VARLONG_SIZE);
                            _bs = bs;
                            cap = _bs.length;
                        }
                        p = writeVarint64At(_bs, p, v);
                    }
                }
                writePackedIntEnd(oldPos, reserve, p);
                return;
            }
            case UINT32: {
                // uint32 是无符号语义（-1 ⇒ FF FF FF FF 0F）⇒ 永远 ≤5 字节 ⇒ 不需要检查容量
                int reserve = varintLen(size * MAX_VARINT_SIZE);
                int oldPos = writePackedIntStart(fieldData, reserve, size * MAX_VARINT_SIZE);
                byte[] _bs = bs;
                int p = oldPos + reserve;
                Iterator<Integer> it = values.iterator();
                while (it.hasNext()) {
                    int v = it.next().intValue();
                    if ((v >>> 14) == 0) {
                        if ((v >>> 7) == 0) {
                            _bs[p++] = (byte) v;
                        } else {
                            _bs[p++] = (byte) (v | -128);
                            _bs[p++] = (byte) (v >>> 7);
                        }
                    } else {
                        p = writeVarintAt3to5(_bs, p, v);
                    }
                }
                writePackedIntEnd(oldPos, reserve, p);
                return;
            }
            case SINT32: {
                // zigzag 后的 int32 最多 5 字节 ⇒ 预留已够，不需要检查容量
                int reserve = varintLen(size * MAX_VARINT_SIZE);
                int oldPos = writePackedIntStart(fieldData, reserve, size * MAX_VARINT_SIZE);
                byte[] _bs = bs;
                int p = oldPos + reserve;
                Iterator<Integer> it = values.iterator();
                while (it.hasNext()) {
                    int v = encodeZigZag32(it.next().intValue());
                    if ((v >>> 14) == 0) {
                        if ((v >>> 7) == 0) {
                            _bs[p++] = (byte) v;
                        } else {
                            _bs[p++] = (byte) (v | -128);
                            _bs[p++] = (byte) (v >>> 7);
                        }
                    } else {
                        p = writeVarintAt3to5(_bs, p, v);
                    }
                }
                writePackedIntEnd(oldPos, reserve, p);
                return;
            }
            case FIXED32:
            case SFIXED32:
                expand((MAX_VARINT_SIZE << 1) + (size << 2));
                writeFieldData(fieldData);
                writeUInt32_0(size << 2);
                for (Integer v : values) {
                    writeFixed32_0(v);
                }
                return;
            default:
                break;
        }
    }

    @Override
    public void writePackedFloats(byte[] fieldData, float[] values) {
        if (values == null || values.length == 0) {
            return;
        }
        int len = values.length << 2;
        expand((MAX_VARINT_SIZE << 1) + len);
        writeFieldData(fieldData);
        writeUInt32_0(len);
        for (float value : values) {
            writeFixed32_0(Float.floatToRawIntBits(value));
        }
    }

    @Override
    public void writePackedFloats(byte[] fieldData, Float[] values) {
        if (values == null || values.length == 0) {
            return;
        }
        int len = values.length << 2;
        expand((MAX_VARINT_SIZE << 1) + len);
        writeFieldData(fieldData);
        writeUInt32_0(len);
        for (float value : values) {
            writeFixed32_0(Float.floatToRawIntBits(value));
        }
    }

    @Override
    public void writePackedFloats(byte[] fieldData, List<Float> values) {
        if (isEmpty(values)) {
            return;
        }
        int len = values.size() << 2;
        expand((MAX_VARINT_SIZE << 1) + len);
        writeFieldData(fieldData);
        writeUInt32(len);
        for (Float value : values) {
            writeFixed32_0(Float.floatToRawIntBits(value));
        }
    }

    @Override
    public void writePackedFloats(byte[] fieldData, Iterable<Float> values) {
        if (isEmpty(values)) {
            return;
        }
        int size = 0;
        for (Float f : values) {
            size++;
        }
        int len = size << 2;
        expand((MAX_VARINT_SIZE << 1) + len);
        writeFieldData(fieldData);
        writeUInt32(len);
        for (Float value : values) {
            writeFixed32_0(Float.floatToRawIntBits(value));
        }
    }

    @Override
    public void writePackedBooleans(byte[] fieldData, boolean[] values) {
        if (values == null || values.length == 0) {
            return;
        }
        expand(MAX_VARINT_SIZE + values.length);
        writeFieldData(fieldData);
        writeUInt32(values.length);
        for (boolean value : values) {
            writeUInt32_0(value?1:0);
        }
    }

    @Override
    public void writePackedBooleans(byte[] fieldData, Boolean[] values) {
        if (values == null || values.length == 0) {
            return;
        }
        expand(MAX_VARINT_SIZE + values.length);
        writeFieldData(fieldData);
        writeUInt32(values.length);
        for (boolean value : values) {
            writeUInt32_0(value?1:0);
        }
    }

    @Override
    public void writePackedBooleans(byte[] fieldData, List<Boolean> values) {
        if (isEmpty(values)) {
            return;
        }
        expand(MAX_VARINT_SIZE + values.size());
        writeFieldData(fieldData);
        writeUInt32(values.size());
        for (boolean value : values) {
            writeUInt32_0(value?1:0);
        }
    }

    @Override
    public void writePackedBooleans(byte[] fieldData, Iterable<Boolean> values) {
        if (values==null || !values.iterator().hasNext()) {
            return;
        }
        int size = 0;
        for (Boolean b : values) {
            size++;
        }
        expand(MAX_VARINT_SIZE + size);
        writeFieldData(fieldData);
        writeUInt32(size);
        for (boolean value : values) {
            writeUInt32_0(value?1:0);
        }
    }

    @Override
    public void writePackedLongs(byte[] fieldData, Long[] values, Field.Type type) {
        if (values == null || values.length == 0) {
            return;
        }
        int len;
        int size = values.length;
        // ★ 长度前缀的预留字节数（见 writePackedInts(int[]) 的说明）
        int reserve = varintLen(size * MAX_VARLONG_SIZE);
        switch (type) {
            case INT64:
            case UINT64:
                expand(MAX_VARINT_SIZE + reserve);
                writeFieldData(fieldData);
                int oldPos = pos;
                pos += reserve;
                expand(MAX_VARLONG_SIZE * values.length);
                for (int i=0;i<size;i++) {
                    writeUInt64_0(values[i]);
                }
                len = pos - oldPos - reserve;
                pos = oldPos + writeLenPrefixReserve(oldPos, reserve, len);
                return;
            case SINT64:
                expand(MAX_VARINT_SIZE + reserve);
                writeFieldData(fieldData);
                oldPos = pos;
                pos += reserve;
                expand(MAX_VARLONG_SIZE * values.length);
                for (int i=0;i<size;i++) {
                    writeUInt64_0(encodeZigZag64(values[i]));
                }
                len = pos - oldPos - reserve;
                pos = oldPos + writeLenPrefixReserve(oldPos, reserve, len);
                return;
            case FIXED64:
            case SFIXED64:
                expand((MAX_VARINT_SIZE << 1) + (size << 3));
                writeFieldData(fieldData);
                writeUInt32_0(size << 3);
                for (int i=0;i<size;i++) {
                    writeFixed64_0(values[i]);
                }
            default:

        }
    }

    @Override
    public void writePackedLongs(byte[] fieldData, long[] values, Field.Type type) {
        if (values == null || values.length == 0) {
            return;
        }
        int len;
        int size = values.length;
        // ★ 长度前缀的预留字节数（见 writePackedInts(int[]) 的说明）
        int reserve = varintLen(size * MAX_VARLONG_SIZE);
        switch (type) {
            case INT64:
            case UINT64:
                expand((MAX_VARINT_SIZE << 1) + MAX_VARLONG_SIZE * values.length + reserve);
                writeFieldData(fieldData);
                int oldPos = pos;
                pos += reserve;
                for (int i=0;i<size;i++) {
                    writeUInt64_0(values[i]);
                }
                len = pos - oldPos - reserve;
                pos = oldPos + writeLenPrefixReserve(oldPos, reserve, len);
                return;
            case SINT64:
                expand((MAX_VARINT_SIZE << 1) + MAX_VARLONG_SIZE * values.length + reserve);
                writeFieldData(fieldData);
                oldPos = pos;
                pos += reserve;
                for (int i=0;i<size;i++) {
                    writeUInt64_0(encodeZigZag64(values[i]));
                }
                len = pos - oldPos - reserve;
                pos = oldPos + writeLenPrefixReserve(oldPos, reserve, len);
                return;
            case FIXED64:
            case SFIXED64:
                expand((MAX_VARINT_SIZE << 1) + (size << 3));
                writeFieldData(fieldData);
                writeUInt32_0(size << 3);
                for (int i=0;i<size;i++) {
                    writeFixed64_0(values[i]);
                }
            default:

        }
    }

    @Override
    public void writePackedLongs(byte[] fieldData, List<Long> values, Field.Type type) {
        if (isEmpty(values)) {
            return;
        }
        int len;
        // ★ 长度前缀的预留字节数（见 writePackedInts(int[]) 的说明）
        int reserve = varintLen(values.size() * MAX_VARLONG_SIZE);
        switch (type) {
            case INT64:
            case UINT64:
                expand((MAX_VARINT_SIZE << 1) + MAX_VARLONG_SIZE * values.size() + reserve);
                writeFieldData(fieldData);
                int oldPos = pos;
                pos += reserve;
                for (long l : values) {
                    writeUInt64_0(l);
                }
                len = pos - oldPos - reserve;
                pos = oldPos + writeLenPrefixReserve(oldPos, reserve, len);
                return;
            case SINT64:
                expand((MAX_VARINT_SIZE << 1) + MAX_VARLONG_SIZE * values.size() + reserve);
                writeFieldData(fieldData);
                oldPos = pos;
                pos += reserve;
                for (long l : values) {
                    writeUInt64_0(encodeZigZag64(l));
                }
                len = pos - oldPos - reserve;
                pos = oldPos + writeLenPrefixReserve(oldPos, reserve, len);
                return;
            case FIXED64:
            case SFIXED64:
                int size = values.size();
                expand((MAX_VARINT_SIZE << 1) + (size << 3));
                writeFieldData(fieldData);
                writeUInt32_0(size << 3);
                for (long l : values) {
                    writeFixed64_0(l);
                }
            default:

        }
    }

    @Override
    public void writePackedLongs(byte[] fieldData, Iterable<Long> values, Field.Type type) {
        if (values == null || !values.iterator().hasNext()) {
            return;
        }
        int len;
        switch (type) {
            case INT64:
            case UINT64:
                len = 0;
                for (long l : values) {
                    len += computeRawVarint64Size(l);
                }
                expand((MAX_VARINT_SIZE << 1) + len);
                writeFieldData(fieldData);
                writeUInt32_0(len);
                for (long l : values) {
                    writeUInt64_0(l);
                }
                return;
            case SINT64:
                len = 0;
                for (long l : values) {
                    len += computeRawVarint64Size(encodeZigZag64(l));
                }
                expand((MAX_VARINT_SIZE << 1) + len);
                writeFieldData(fieldData);
                writeUInt32_0(len);
                for (long l : values) {
                    writeUInt64_0(encodeZigZag64(l));
                }
                return;
            case FIXED64:
            case SFIXED64:
                int size = 0;
                for (long l : values) {
                    size++;
                }
                expand((MAX_VARINT_SIZE << 1) + (size << 3));
                writeFieldData(fieldData);
                writeUInt32_0(size << 3);
                for (long l : values) {
                    writeFixed64_0(l);
                }
            default:

        }
    }

    @Override
    public void writePackedDoubles(byte[] fieldData, double[] values) {
        if (values == null || values.length == 0) {
            return;
        }
        int len = values.length << 3;
        expand((MAX_VARINT_SIZE << 1) + len);
        writeFieldData(fieldData);
        writeUInt32_0(len);
        for (double value : values) {
            writeFixed64_0(Double.doubleToLongBits(value));
        }
    }

    @Override
    public void writePackedDoubles(byte[] fieldData, Double[] values) {
        if (values == null || values.length == 0) {
            return;
        }
        int len = values.length << 3;
        expand((MAX_VARINT_SIZE << 1) + len);
        writeFieldData(fieldData);
        writeUInt32_0(len);
        for (Double value : values) {
            double d = value==null?0:value.doubleValue();
            writeFixed64_0(Double.doubleToLongBits(d));
        }
    }

    @Override
    public void writePackedDoubles(byte[] fieldData, List<Double> values) {
        if (isEmpty(values)) {
            return;
        }
        int len = values.size() << 3;
        expand((MAX_VARINT_SIZE << 1) + len);
        writeFieldData(fieldData);
        writeUInt32_0(len);
        for (Double value : values) {
            writeFixed64_0(Double.doubleToLongBits(value));
        }
    }

    @Override
    public void writePackedDoubles(byte[] fieldData, Iterable<Double> values) {
        if (values == null || !values.iterator().hasNext()) {
            return;
        }
        int len = 0;
        for (Double value : values) {
            len++;
        }
        expand((MAX_VARINT_SIZE << 1) + len);
        writeFieldData(fieldData);
        writeUInt32_0(len);
        for (Double value : values) {
            writeFixed64_0(Double.doubleToLongBits(value));
        }
    }

    @Override
    public <E extends Enum<E>> void writeArrayEnum(byte[] fieldData, E[] values) {
        int size = values.length;
        int len;
        // ★ 长度前缀的预留字节数（见 writePackedInts(int[]) 的说明）
        int reserve = varintLen(size * MAX_VARINT_SIZE);
        expand((MAX_VARINT_SIZE << 1) + size * MAX_VARINT_SIZE + reserve);
        writeFieldData(fieldData);
        int oldPos = pos;

        pos += reserve;
        int i = 0;
        writeInt32_0(values[i++].ordinal());
        if (size > 1) {
            writeInt32_0(values[i++].ordinal());
        }
        if (size > 2) {
            writeInt32_0(values[i++].ordinal());
        }
        if (size > 3) {
            writeInt32_0(values[i++].ordinal());
        }
        if (size > 4) {
            writeInt32_0(values[i++].ordinal());
        }
        if (size > 5) {
            writeInt32_0(values[i++].ordinal());
        }
        if (size > 6) {
            writeInt32_0(values[i++].ordinal());
        }
        if (size > 7) {
            writeUInt32_0(values[i++].ordinal());
        }
        if (size > 8) {
            writeInt32_0(values[i++].ordinal());
        }
        if (size > 9) {
            writeInt32_0(values[i++].ordinal());
        }
        if (size > 10) {
            for (i=10;i<size;i++) {
                writeInt32_0(values[i].ordinal());
            }
        }
        len = pos - oldPos - reserve;
        pos = oldPos + writeLenPrefixReserve(oldPos, reserve, len);
    }

    @Override
    public void writeObject(byte[] fieldData, Object v, ProtoBufOption option) throws EncodeException {
        expand(MAX_VARINT_SIZE);
        writeFieldData(fieldData);
        AnyCodec.encode(this, v);
    }

    @Override
    public void writeObject(Object v) throws EncodeException {
        if (null == v) {
            return;
        }
        AnyCodec.encode(this, v);
    }

    public void writeObjects(Iterator<Object> objects) throws EncodeException {
        if (null == objects) {
            return;
        }
        int oldPos = pos;
        pos += 1;
        int len = 0;
        while (objects.hasNext()) {
            len++;
            AnyCodec.encode(this, objects.next());
        }
        pos += writeLenMoveBytes(bs, oldPos, len);
    }

    @Override
    public <K, V> void writeMap(byte[] fieldData, int tag, Map<K, V> map, MapEntryEncoder<K, V> mapEncoder) throws EncodeException {
        if (CollectionUtils.isEmpty(map)) {
            return;
        }
        for (Map.Entry<K, V> entry : map.entrySet()) {
            expand(MAX_VARINT_SIZE);
            writeFieldData(fieldData);
            int oldPos = pos;
            pos += 1;
            mapEncoder.encode(this, entry);
            int len = pos - oldPos - 1;
            pos += writeLenMoveBytes(bs, oldPos, len);
        }
    }

    @Override
    public <K, V> void writeMapMessage(byte[] fieldData, int tag, Map<K, V> map, MapEntryEncoder<K, V> mapEncoder) throws EncodeException {
        if (CollectionUtils.isEmpty(map)) {
            return;
        }
        expand(MAX_VARINT_SIZE);
        writeFieldData(fieldData);

        int outOldPos = pos;

        // ★ 这里<b>不能</b>用「预留 NESTED_LEN_RESERVE 字节 + 回填」的写法：
        //   旧实现是「不预留前缀字节，写完内容后把长度前缀写到 outOldPos，
        //   正好覆盖掉内容的第一个字节（map entry 的 tag）」，
        //   而解码侧（ByteArrayReader.readMap → MapEntryDecoder）<b>不读</b>这个 entry tag
        //   ⇒ 编码与解码是这么对上的。
        //   一旦改成「预留 2 字节」，entry tag 就会<b>多出来一个</b> ⇒ 解码侧错位。
        byte[] mapMsgTagData = ProtoUtil.buildFieldData(1, Field.Type.MAP, Field.Cardinality.REPEATED);
        boolean needFieldData = true;
        for (Map.Entry<K, V> entry : map.entrySet()) {
            if (needFieldData) {
                expand(MAX_VARINT_SIZE);
                writeFieldData(mapMsgTagData);
                needFieldData = false;
            }
            mapEncoder.encode(this, entry);
        }
        int len = pos - outOldPos - 1;
        pos += writeLenMoveBytes(bs, outOldPos, len);
    }

    @Override
    public <T> void writeMessage(T v, ProtoBufEncoder<T> codec) throws EncodeException {
        int oldPos = pos;
        // ★ 预留 2 字节给长度前缀：嵌套消息载荷绝大多数落在 128..16383 ⇒ 前缀正好 2 字节
        //   ⇒ 写完不用整块搬移（旧实现只预留 1 字节 ⇒ 载荷 ≥128 字节时必然右移一次）
        expand(NESTED_LEN_RESERVE - 1);
        pos += NESTED_LEN_RESERVE;
        codec.encode(this, v);
        int len = pos - oldPos - NESTED_LEN_RESERVE;
        pos = oldPos + writeLenPrefixReserve(oldPos, NESTED_LEN_RESERVE, len);
    }

    @Override
    public <T> void writeMessage(byte[] fieldData, int tag, T v, ProtoBufEncoder<T> codec) throws EncodeException {
        if (v == null) {
            return;
        }
        writeMessage0(fieldData, tag, v, codec);
    }

    public <T> void writeMessage0(byte[] fieldData, int tag, T v, ProtoBufEncoder<T> codec) throws EncodeException {
        int len;
        // tag(≤5 字节) + 预留的 2 字节长度前缀
        expand(MAX_VARINT_SIZE + NESTED_LEN_RESERVE);
        writeFieldData(fieldData);
        int oldPos = pos;
        pos += NESTED_LEN_RESERVE;
        codec.encode(this, v);
        len = pos - oldPos - NESTED_LEN_RESERVE;
        pos = oldPos + writeLenPrefixReserve(oldPos, NESTED_LEN_RESERVE, len);
    }

    @Override
    public <T> void writeMessages(byte[] fieldData, int tag, T[] vs, ProtoBufEncoder<T> codec) throws EncodeException {
        if (vs == null) {
            return;
        }
        int size = vs.length;
        for (int i=0;i<size;i++) {
            //for (T v : vs) {
            T v = vs[i];
            writeMessage0(fieldData, tag, v, codec);
        }
    }

    @Override
    public <T> void writeMessages(byte[] fieldData, int tag, List<T> vs, ProtoBufEncoder<T> codec) throws EncodeException {
        if (CollectionUtils.isEmpty(vs)) {
            return;
        }
        int size = vs.size();
        int len;
        for (int i=0;i<size;i++) {
            //for (T v : vs) {
            T v = vs.get(i);
            writeMessage0(fieldData, tag, v, codec);
        }
    }

    @Override
    public <T> void writeMessages(byte[] fieldData, int tag, Iterable<T> vs, ProtoBufEncoder<T> codec) throws EncodeException {
        if (vs == null || !vs.iterator().hasNext()) {
            return;
        }
        for (T v : vs) {
            writeMessage0(fieldData, tag, v, codec);
        }
    }

}
