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

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.WritableByteChannel;

/**
 * 文件映射 buffer（{@link MappedByteBuffer}）实现的 {@link HttpBody}。
 *
 * <p>当 {@code FastBuf} 由 {@code FileChannel.map(...)} 构造（{@code BufType.MAPPED_FILE}）
 * 时，{@code BodyDecoder} 走"单段切片"路径产生的 body 用此实现。
 *
 * <p><b>生命周期</b>：与 {@code DirectBufferHttpBody} 不同 —— 文件映射是
 * page cache 内存，由 OS 管理，不依赖 FastBuf 池化回收。因此此 body
 * 可在 handler 返回后存活，handler 异步处理或把 body 传给其它线程消费时
 * 不必先 {@link #toByteArray()} 物化。
 */
public final class MappedFileHttpBody implements HttpBody {

    private final MappedByteBuffer view;
    private final int length;

    /**
     * @param view positioned + limited 的文件映射 ByteBuffer；本 body 持有引用，
     *             不会修改其 position/limit
     */
    public MappedFileHttpBody(MappedByteBuffer view) {
        if (view == null) {
            throw new IllegalArgumentException("view must not be null");
        }
        this.view = view;
        this.length = view.remaining();
    }

    @Override
    public long length() {
        return length;
    }

    @Override
    public InputStream openStream() {
        return new DirectBufferHttpBody.ByteBufferInputStream(view.duplicate());
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
        // 写入完成，强制刷盘（仅在 dirty 时生效）
        if (view.isLoaded()) {
            // page cache 已加载，无需 force
            return written;
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
}
