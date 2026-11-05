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

package io.edap.http.server;

import io.edap.Decoder;
import io.edap.buffer.FastBuf;
import io.edap.http.HttpRequest;
import io.edap.nio.ParseResult;
import io.edap.pool.Pool;

import java.io.IOException;

/**
 * HTTP/1.1 连接策略。
 *
 * <p>解码仍然走既有的 {@link RangeHttpRequestDecoder}（它是一个<b>基于 session 状态</b>的解析状态机：
 * {@code HttpNioSession.getDecodeState()} 保存续解析位置），所以本类只负责两件事：</p>
 *
 * <ol>
 *   <li>把「协议嗅探阶段暂存的 1~2 个字节」回放给解码器（否则首字节被吞掉，HTTP/1.1 直接废掉）；</li>
 *   <li>在请求跨 read 拆分时，把未消费完的字节留到下一次 read。</li>
 * </ol>
 *
 * <p><b>快路径</b>：没有暂存字节时，行为与改造前<b>逐字节一致</b>（同一个 decoder、同一段循环、
 * 同一个缓冲），HTTP/1.1 输出零变化。暂存字节只在「首次 read 不足 3 字节」这种边界情况出现，
 * 改造前这种请求本来就会被吞掉首字节而解析失败。</p>
 */
public class Http1Connection implements HttpConnection {

    private final HttpServerNioSession session;

    /**
     * 尚未喂给解码器的字节（嗅探暂存字节 + 上一次 read 未消费完的尾巴）。
     */
    private byte[] pending = new byte[0];
    private int pendingLen;

    public Http1Connection(HttpServerNioSession session, byte[] carry) {
        this.session = session;
        if (carry != null && carry.length > 0) {
            this.pending = carry;
            this.pendingLen = carry.length;
        }
    }

    @Override
    public boolean decode(FastBuf buf) throws IOException {
        if (pendingLen == 0) {
            return decodeFast(buf);
        }
        return decodeWithPending(buf);
    }

    @Override
    public void flush(Pool<FastBuf> bbPool) throws IOException {
        // ★ 必须调 flushSessionBuffer 而不是 session.flush ——
        //   session.flush 会被 HttpServerNioSession 覆写成「委派给 connection.flush」，
        //   这里再调 session.flush 就成了自己调自己（无限递归 → StackOverflowError）。
        session.flushSessionBuffer(bbPool);
    }

    /**
     * 快路径：无暂存字节，直接把缓冲交给既有的 HTTP/1.1 解码器。
     *
     * <p>与改造前 {@code HttpServerNioSession.decode} 的 else 分支完全一致。</p>
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private boolean decodeFast(FastBuf buf) throws IOException {
        boolean hasMsg = false;
        Decoder decoder = session.getDecoder();
        while (buf.remain() > 0) {
            ParseResult pr = decoder.decode(buf, session);
            if (!pr.isFinished()) {
                break;
            }
            hasMsg = true;
            HttpRequest request = (HttpRequest) pr.getMessage();
            // ★ h2c 升级（RFC 9113 §3.2）：升级请求不走业务 handler，
            //   连接对象会被换成 HTTP/2，残留字节（如果有）交给它处理。
            if (H2cUpgrade.tryUpgrade(session, request, buf)) {
                return true;
            }
            session.handle(request);
        }
        return hasMsg;
    }

    /**
     * 慢路径：把暂存字节与本次 read 的内容拼成一个<b>连续</b>缓冲再喂给解码器。
     *
     * <p>必须拼成连续的：解码器是按「字节流」解析的，把 "G" 和 "ET / HTTP/1.1\r\n\r\n"
     * 分两次喂过去，"G" 会被当成一个独立的 method 解析掉。</p>
     *
     * <p>解码后未消费完的字节留到下一次 read —— 这同时修掉了「HTTP/1.1 请求跨 read 拆分时
     * 字节丢失」的老问题（只影响本来就已经坏掉的路径）。</p>
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private boolean decodeWithPending(FastBuf buf) throws IOException {
        int remain = buf.remain();
        FastBuf combined = new FastBuf(pendingLen + remain);
        combined.write(pending, 0, pendingLen);
        if (remain > 0) {
            byte[] tail = new byte[remain];
            buf.get(tail);
            combined.write(tail);
        }
        combined.rewind();

        boolean hasMsg = false;
        Decoder decoder = session.getDecoder();
        while (combined.remain() > 0) {
            ParseResult pr = decoder.decode(combined, session);
            if (!pr.isFinished()) {
                break;
            }
            hasMsg = true;
            HttpRequest request = (HttpRequest) pr.getMessage();
            // ★ h2c 升级（RFC 9113 §3.2）
            if (H2cUpgrade.tryUpgrade(session, request, combined)) {
                pendingLen = 0;
                return true;
            }
            session.handle(request);
        }

        int left = combined.remain();
        if (left > 0) {
            byte[] rest = new byte[left];
            combined.get(rest);
            pending = rest;
            pendingLen = left;
        } else {
            pendingLen = 0;
        }
        return hasMsg;
    }

    /**
     * 当前尚未喂给解码器的字节数（测试用）。
     */
    public int getPendingLen() {
        return pendingLen;
    }
}
