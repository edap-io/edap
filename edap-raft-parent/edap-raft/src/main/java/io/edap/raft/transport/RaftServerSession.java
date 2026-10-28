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

package io.edap.raft.transport;

import io.edap.NioServerSession;
import io.edap.buffer.FastBuf;
import io.edap.nio.ParseResult;
import io.edap.raft.protocol.Message;

import java.io.IOException;

/**
 * raft 的入站连接会话。
 *
 * <p>框架的读缓冲每次读之前都会 {@code reset()}，没有读完整的一帧会被丢弃，
 * 所以这里自己维护一个跨 read 的累积缓冲 {@link RaftFrameReader}。</p>
 *
 * <p>{@link #handle(Message)} 跑在 NIO 的 IO 线程上，不能阻塞，
 * 实现方应当把消息投递到自己的状态机线程。</p>
 */
public class RaftServerSession extends NioServerSession<Message> {

    private final RaftDecoder    raftDecoder = new RaftDecoder();
    /** 跨 read 的累积缓冲 */
    private final RaftFrameReader frameReader = new RaftFrameReader();

    private volatile RaftMessageHandler handler;

    public RaftServerSession() {
        setDecoder(raftDecoder);
    }

    /**
     * 设置入站消息的处理器
     */
    public void setHandler(RaftMessageHandler handler) {
        this.handler = handler;
    }

    @Override
    public boolean decode(FastBuf fastBuf, boolean threadSwitch) throws IOException {
        boolean hasMessage = false;
        while (true) {
            ParseResult<Message> result = raftDecoder.decode(fastBuf, this);
            if (!result.isFinished()) {
                break;
            }
            hasMessage = true;
            handle(result.getMessage());
        }
        return hasMessage;
    }

    @Override
    public void handle(Message message) throws IOException {
        RaftMessageHandler h = handler;
        if (h != null) {
            h.onMessage(message);
        }
    }

    /**
     * 把读到的字节喂进累积缓冲，并取出一条完整的消息。
     *
     * @return 消息不完整时返回 finished=false
     */
    public ParseResult<Message> parseFrame(FastBuf bufIn) {
        ParseResult<Message> result = new ParseResult<>();
        int remain = bufIn == null ? 0 : bufIn.remain();
        if (remain > 0) {
            byte[] data = new byte[remain];
            bufIn.get(data);
            frameReader.append(data, 0, remain);
        }
        Message message = frameReader.next();
        if (message == null) {
            result.setFinished(false);
            return result;
        }
        result.setFinished(true);
        result.setMessage(message);
        return result;
    }
}
