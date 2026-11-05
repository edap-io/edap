/*
 * Copyright (c) 2019 louis.lu
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */

package io.edap.http.core.test.rangedecoder;

import io.edap.buffer.FastBuf;
import io.edap.http.HttpBody;
import io.edap.http.HttpNioSession;
import io.edap.http.HttpRequest;
import io.edap.http.MethodInfo;
import io.edap.http.ValueHttpRequest;
import io.edap.http.body.DirectBufferHttpBody;
import io.edap.http.body.HeapHttpBody;
import io.edap.http.body.MappedFileHttpBody;
import io.edap.http.codec.HttpFastBufDataRange;
import io.edap.http.rangedecoder.BodyDecoder;
import io.edap.nio.ParseResult;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class BodyDecoderTest {

    /**
     * 跨多次 socket read 的 body 必须正确累积。
     * 验证 hasLen 修复：原实现 hasLen 永远 0，多轮 read 永远完成不了。
     */
    @Test
    public void testMultiReadAccumulation() {
        int totalLen = 64 * 1024;  // 64 KiB body
        byte[] payload = new byte[totalLen];
        for (int i = 0; i < totalLen; i++) {
            payload[i] = (byte) (i & 0xff);
        }

        HttpNioSession session = newSession();
        HttpRequest req = newRequest(totalLen);
        // 64K 容量足够放整个 64K body；模拟多次 decode 调用时 buf 的写入量逐步累加
        FastBuf buf = new FastBuf(totalLen);
        ParseResult<HttpRequest> result = newResult();

        BodyDecoder decoder = new BodyDecoder();
        boolean finished = false;

        // 第一波：写入 10 KiB（不足），期望未完成
        buf.write(payload, 0, 10 * 1024);
        decoder.decode(req, buf, new HttpFastBufDataRange(), result, session);
        finished = result.isFinished();
        assertFalse(finished, "first chunk of 10K should not finish a 64K body");

        // 第二波：再写入 20 KiB（共 30 KiB），仍不足
        buf.write(payload, 10 * 1024, 20 * 1024);
        result = newResult();
        decoder.decode(req, buf, new HttpFastBufDataRange(), result, session);
        assertFalse(result.isFinished(), "30K should not finish a 64K body");

        // 第三波：写入剩余 34 KiB（恰好收齐），期望完成
        buf.write(payload, 30 * 1024, totalLen - 30 * 1024);
        result = newResult();
        decoder.decode(req, buf, new HttpFastBufDataRange(), result, session);
        assertTrue(result.isFinished(), "final chunk should finish body");

        HttpBody body = req.getBody();
        assertInstanceOf(DirectBufferHttpBody.class, body, "16MB-class body should be direct");
        assertEquals(totalLen, body.length());
        assertArrayEquals(payload, body.toByteArray(), "accumulated body must match payload exactly");
    }

    /**
     * 单次 read 已收齐 body → 直接 buffer 累加路径（body 必须是 DirectBufferHttpBody）。
     */
    @Test
    public void testSingleShotDirectBuffer() {
        int totalLen = 8 * 1024;
        byte[] payload = new byte[totalLen];
        for (int i = 0; i < totalLen; i++) {
            payload[i] = (byte) ((i * 7) & 0xff);
        }

        HttpNioSession session = newSession();
        HttpRequest req = newRequest(totalLen);
        FastBuf buf = new FastBuf(totalLen);
        buf.write(payload);

        BodyDecoder decoder = new BodyDecoder();
        ParseResult<HttpRequest> result = newResult();
        decoder.decode(req, buf, new HttpFastBufDataRange(), result, session);
        assertTrue(result.isFinished());

        HttpBody body = req.getBody();
        assertInstanceOf(DirectBufferHttpBody.class, body);
        assertEquals(totalLen, body.length());
        assertArrayEquals(payload, body.toByteArray());
    }

    /**
     * 单次 read + FastBuf 是 MAPPED_FILE → 直接走 MappedFileHttpBody 路径
     * （零拷贝 page cache 直接出）。
     */
    @Test
    public void testSingleShotMappedFile() throws IOException {
        Path tmp = Files.createTempFile("edap-bodytest-", ".bin");
        tmp.toFile().deleteOnExit();
        int totalLen = 4096;
        byte[] payload = new byte[totalLen];
        for (int i = 0; i < totalLen; i++) {
            payload[i] = (byte) ((i ^ 0xa5) & 0xff);
        }

        HttpNioSession session = newSession();
        HttpRequest req = newRequest(totalLen);
        FastBuf buf = new FastBuf(totalLen, tmp.toString());
        buf.write(payload);

        BodyDecoder decoder = new BodyDecoder();
        ParseResult<HttpRequest> result = newResult();
        decoder.decode(req, buf, new HttpFastBufDataRange(), result, session);
        assertTrue(result.isFinished());

        HttpBody body = req.getBody();
        assertInstanceOf(MappedFileHttpBody.class, body, "MAPPED_FILE single-shot must use MappedFileHttpBody");
        assertEquals(totalLen, body.length());
        assertArrayEquals(payload, body.toByteArray());
    }

    /**
     * HeapHttpBody 是接口实现样板（Step 1 占位）：验证基本读写一致。
     */
    @Test
    public void testHeapHttpBodyRoundtrip() throws IOException {
        byte[] payload = "hello edap HttpBody".getBytes();
        HttpBody body = new HeapHttpBody(payload);
        assertEquals(payload.length, body.length());
        assertTrue(body.isHeap());
        assertArrayEquals(payload, body.toByteArray());

        // openStream 流式读取
        try (InputStream in = body.openStream()) {
            ByteArrayOutputStream sink = new ByteArrayOutputStream();
            byte[] chunk = new byte[4];
            int n;
            while ((n = in.read(chunk)) != -1) {
                sink.write(chunk, 0, n);
            }
            assertArrayEquals(payload, sink.toByteArray());
        }
    }

    /**
     * DirectBufferHttpBody：openStream / transferTo / writeTo 都应能流式读出全部字节，
     * 不依赖堆上额外副本。
     */
    @Test
    public void testDirectBufferHttpBodyStream() throws IOException {
        int len = 64 * 1024;
        byte[] payload = new byte[len];
        for (int i = 0; i < len; i++) {
            payload[i] = (byte) (i & 0xff);
        }
        ByteBuffer direct = ByteBuffer.allocateDirect(len);
        direct.put(payload);
        direct.flip();

        HttpBody body = new DirectBufferHttpBody(direct);
        assertEquals(len, body.length());
        assertFalse(body.isHeap());

        // 流式读出
        try (InputStream in = body.openStream()) {
            ByteArrayOutputStream sink = new ByteArrayOutputStream();
            byte[] chunk = new byte[1024];
            int n;
            while ((n = in.read(chunk)) != -1) {
                sink.write(chunk, 0, n);
            }
            assertArrayEquals(payload, sink.toByteArray());
        }

        // transferTo
        ByteBufferOutputStream sink2 = new ByteBufferOutputStream();
        long written = body.transferTo(sink2.channel());
        assertEquals(len, written);
        assertArrayEquals(payload, sink2.toByteArray());
    }

    // --------------------- helpers ---------------------

    private static HttpNioSession newSession() {
        return new HttpNioSession() {
            @Override
            public void handle(HttpRequest request) {
                // no-op for tests
            }

            @Override
            public boolean decode(FastBuf fastBuf, boolean threadSwitch) {
                return true;
            }
        };
    }

    private static HttpRequest newRequest(int contentLength) {
        ValueHttpRequest req = new ValueHttpRequest();
        // BodyDecoder.decode 第一行就调 request.getMethod()，需要 methodInfo
        MethodInfo mi = new MethodInfo();
        mi.setMethod("POST");
        req.methodInfo = mi;
        // 通过 Content-Length header 注入，BodyDecoder 会调 request.getContentLength() 解析
        io.edap.http.HeaderValue hv = new io.edap.http.HeaderValue(
                Integer.toString(contentLength));
        req.putHeader("Content-Length", hv);
        return req;
    }

    private static ParseResult<HttpRequest> newResult() {
        return new ParseResult<>();
    }

    /** 简单 OutputStream → WritableByteChannel 桥接，用于 transferTo 测试。 */
    private static final class ByteBufferOutputStream extends java.io.OutputStream {
        private final java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();

        java.nio.channels.WritableByteChannel channel() {
            return java.nio.channels.Channels.newChannel(this);
        }

        @Override
        public void write(int b) {
            buf.write(b);
        }

        @Override
        public void write(byte[] b, int off, int len) {
            buf.write(b, off, len);
        }

        byte[] toByteArray() {
            return buf.toByteArray();
        }
    }
}
