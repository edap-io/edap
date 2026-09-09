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

package io.edap.http;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.WritableByteChannel;

/**
 * 请求 body 的零拷贝抽象。
 *
 * <p>由 {@code BodyDecoder} 在请求 body 完整收齐后产出，并通过
 * {@link HttpRequest#setBody(HttpBody)} 挂到请求对象上。多个实现覆盖不同的
 * 内存形态：堆上 byte 数组（{@link io.edap.http.body.HeapHttpBody}）、
 * 堆外直接 buffer（{@link io.edap.http.body.DirectBufferHttpBody}）、
 * 文件映射 buffer（{@link io.edap.http.body.MappedFileHttpBody}）。
 *
 * <p><b>生命周期契约</b>：除 {@code MappedFileHttpBody}（file-backed，可跨
 * handler 调用存活）外，其它实现都持有对底层池化 buffer 的引用，handler
 * 调用返回后即失效。任何需要把 body 传出 handler 调用范围（异步、队列、
 * 缓存等）的代码必须先调用 {@link #toByteArray()} 物化到堆上。
 *
 * <p>所有方法在 handler 同步调用期间行为已定义；超出该窗口期的行为未定义。
 */
public interface HttpBody {

    /**
     * @return body 字节数；返回后不变。
     */
    long length();

    /**
     * 返回 body 的流式视图。读到的字节即为 body 内容；不会预拷贝整个 body。
     *
     * <p>返回的流通常不是线程安全的，使用方应在单线程内消费并关闭。
     *
     * @return 输入流；返回 null 表示空 body
     * @throws IOException 打开流失败
     */
    InputStream openStream() throws IOException;

    /**
     * 零拷贝（或近零拷贝）写出到 OutputStream。适合 S3 小 body、XML
     * metadata 这类需要把整个 body 一次性灌到下游 OutputStream 的场景。
     *
     * @param out 目标流，不能为 null
     * @throws IOException 写出过程中 I/O 失败
     */
    void writeTo(OutputStream out) throws IOException;

    /**
     * 零拷贝写出到 WritableByteChannel。适合 {@code DiskObjectStore} 等
     * 直接落盘的下游，能完全绕开堆上中间缓冲。
     *
     * @param ch 目标通道，不能为 null
     * @return 实际写入的字节数
     * @throws IOException 写出过程中 I/O 失败
     */
    long transferTo(WritableByteChannel ch) throws IOException;

    /**
     * @return ByteBuffer 视图。heap 实现返回 {@code ByteBuffer.wrap}，
     *         direct/mapped 实现返回底层 ByteBuffer 的 positioned、limited、
     *         只读切片（{@code duplicate().asReadOnlyBuffer()}）。
     *         该视图的生命周期遵循 HttpBody 的总体契约。
     */
    ByteBuffer toByteBuffer();

    /**
     * 把 body 物化到堆上 byte[]。这是显式接受堆拷贝成本的逃生口，
     * 主要用于 JSON 反序列化（{@code Eson.parseObject(byte[], Class)}）
     * 等需要完整 byte[] 的场景。
     *
     * <p>对大 body 调用此方法等同于把 body 完整读回堆上 —— 调用方必须
     * 意识到代价。如果只是想流式消费，应使用 {@link #openStream()} 或
     * {@link #transferTo(WritableByteChannel)}。
     *
     * @return 堆上 byte[] 副本
     */
    byte[] toByteArray();

    /**
     * @return true 表示 body 在堆上已经是 byte[] 形式（{@code HeapHttpBody}）；
     *         false 表示堆外（{@code DirectBufferHttpBody} 或
     *         {@code MappedFileHttpBody}）。调用方可用于决定是否走堆上快速路径。
     */
    boolean isHeap();
}
