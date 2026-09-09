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

package io.edap.http.body;

import io.edap.buffer.FastBuf;
import io.edap.http.HttpBody;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.WritableByteChannel;

/**
 * 堆外直接 ByteBuffer 实现的 {@link HttpBody}。默认路径：BodyDecoder 收到完整
 * body 后，若 FastBuf 是 MEMORY 类型，会从其底层 ByteBuffer 切一个 positioned、
 * limited、只读视图包成此实现。S3 大 body 走这条路径，handler 经
 * {@link #openStream()} / {@link #transferTo(WritableByteChannel)} 直接消费，
 * 中间不堆拷贝。
 *
 * <p><b>生命周期</b>：持有的 ByteBuffer 视图指向 FastBuf 的池化内存，
 * {@code BodyDecoder} 在每次 socket read 之间会复用 FastBuf。
 * 因此此 body 仅在产生它的同步 handler 调用期间有效；handler 返回后
 * 视图失效。需要跨调用使用应先 {@link #toByteArray()} 物化。
 */
public final class DirectBufferHttpBody implements HttpBody {

    private final ByteBuffer view;
    private final int length;

    /**
     * 用 FastBuf 地址区间构造 body。区间可以是：
     * - 单次 socket read 已收齐的整段 body（最常见，零拷贝视图）。
     * - 跨多次 socket read 经累加得到的堆外 ByteBuffer。
     *
     * @param buf 已 positioned、limited 的 ByteBuffer 视图，调用方保证不再修改其
     *            underlying buffer 的 position/limit
     */
    public DirectBufferHttpBody(ByteBuffer view) {
        if (view == null) {
            throw new IllegalArgumentException("view must not be null");
        }
        // 锁住视图的剩余字节数；view 由调用方保证 positioned + limited 已就绪
        this.view = view;
        this.length = view.remaining();
    }

    /**
     * 从 FastBuf 区间直接构造 body，内部用 {@link FastBuf#byteBufferSlice(long, int)}
     * 切出视图。
     *
     * @param buf FastBuf 实例
     * @param startAddress 起始绝对地址
     * @param length 区间长度
     */
    public static DirectBufferHttpBody fromFastBuf(FastBuf buf, long startAddress, int length) {
        return new DirectBufferHttpBody(buf.byteBufferSlice(startAddress, length));
    }

    @Override
    public long length() {
        return length;
    }

    @Override
    public InputStream openStream() {
        return new ByteBufferInputStream(view.duplicate());
    }

    @Override
    public void writeTo(OutputStream out) throws IOException {
        WritableByteChannel ch = Channels.newChannel(out);
        ByteBuffer dup = view.duplicate();
        while (dup.hasRemaining()) {
            ch.write(dup);
        }
    }

    @Override
    public long transferTo(WritableByteChannel ch) throws IOException {
        ByteBuffer dup = view.duplicate();
        long written = 0;
        while (dup.hasRemaining()) {
            int n = ch.write(dup);
            if (n <= 0) {
                break;
            }
            written += n;
        }
        return written;
    }

    @Override
    public ByteBuffer toByteBuffer() {
        return view.duplicate();
    }

    @Override
    public byte[] toByteArray() {
        byte[] out = new byte[length];
        ByteBuffer dup = view.duplicate();
        dup.get(out);
        return out;
    }

    @Override
    public boolean isHeap() {
        return false;
    }

    /**
     * 简单 ByteBuffer → InputStream 适配器。Channels.newInputStream 不支持直接
     * 接受 ByteBuffer，所以这里包一层。read() 走 ByteBuffer.remaining() 流式消费。
     * 同包其它 HttpBody 实现（{@link MappedFileHttpBody}）复用此适配器。
     */
    static final class ByteBufferInputStream extends InputStream {
        private final ByteBuffer buf;

        ByteBufferInputStream(ByteBuffer buf) {
            this.buf = buf;
        }

        @Override
        public int read() {
            if (!buf.hasRemaining()) {
                return -1;
            }
            return buf.get() & 0xff;
        }

        @Override
        public int read(byte[] b, int off, int len) {
            if (!buf.hasRemaining()) {
                return -1;
            }
            int n = Math.min(len, buf.remaining());
            buf.get(b, off, n);
            return n;
        }

        @Override
        public int available() {
            return buf.remaining();
        }
    }
}
