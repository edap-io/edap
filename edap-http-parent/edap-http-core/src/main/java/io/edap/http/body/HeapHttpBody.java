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

import io.edap.http.HttpBody;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.WritableByteChannel;
import java.util.Arrays;

/**
 * 堆上 byte 数组实现的 {@link HttpBody}。零堆拷贝持有底层 byte[] 切片。
 *
 * <p>当前 {@code BodyDecoder} 默认走 {@code DirectBufferHttpBody}；本实现
 * 保留以备未来按 body 大小/Content-Type 切换、或对小 body 显式选择堆路径
 * （例如希望 JSON handler 完全零拷贝走 byte[]）。
 *
 * <p>生命周期：堆内存由 GC 管理，可在 handler 返回后存活。
 */
public final class HeapHttpBody implements HttpBody {

    private final byte[] bytes;
    private final int offset;
    private final int length;

    public HeapHttpBody(byte[] bytes, int offset, int length) {
        if (bytes == null) {
            throw new IllegalArgumentException("bytes must not be null");
        }
        if (offset < 0 || length < 0 || offset + length > bytes.length) {
            throw new IndexOutOfBoundsException(
                    "offset=" + offset + ", length=" + length + ", bytes.length=" + bytes.length);
        }
        this.bytes = bytes;
        this.offset = offset;
        this.length = length;
    }

    public HeapHttpBody(byte[] bytes) {
        this(bytes, 0, bytes.length);
    }

    @Override
    public long length() {
        return length;
    }

    @Override
    public InputStream openStream() {
        return new ByteArrayInputStream(bytes, offset, length);
    }

    @Override
    public void writeTo(OutputStream out) throws IOException {
        // heap 上零中间拷贝写出：直接 wrap + 通过 WritableByteChannel 桥接
        WritableByteChannel ch = Channels.newChannel(out);
        ByteBuffer buf = ByteBuffer.wrap(bytes, offset, length);
        while (buf.hasRemaining()) {
            ch.write(buf);
        }
    }

    @Override
    public long transferTo(WritableByteChannel ch) throws IOException {
        ByteBuffer buf = ByteBuffer.wrap(bytes, offset, length);
        long written = 0;
        while (buf.hasRemaining()) {
            written += ch.write(buf);
        }
        return written;
    }

    @Override
    public ByteBuffer toByteBuffer() {
        return ByteBuffer.wrap(bytes, offset, length).asReadOnlyBuffer();
    }

    @Override
    public byte[] toByteArray() {
        if (offset == 0 && length == bytes.length) {
            return bytes;
        }
        return Arrays.copyOfRange(bytes, offset, offset + length);
    }

    @Override
    public boolean isHeap() {
        return true;
    }
}
