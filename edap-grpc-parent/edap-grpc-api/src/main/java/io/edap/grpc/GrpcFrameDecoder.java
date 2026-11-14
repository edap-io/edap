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

package io.edap.grpc;

/**
 * gRPC 消息的增量分帧器。
 *
 * <p>HTTP/2 的 DATA 帧与 gRPC 的消息边界没有任何关系 —— 一条消息可能被拆到多个
 * DATA 帧里，多条消息也可能挤在一个 DATA 帧里。这个类把字节流还原成消息流。</p>
 *
 * <p>帧格式（gRPC 规范）：1 字节压缩标志 + 4 字节大端长度 + 消息体。</p>
 *
 * <p>客户端与服务端共用同一份实现 —— 分帧是协议层的事，与角色无关。</p>
 */
public final class GrpcFrameDecoder {

    /**
     * 收到的字节（帧头 + 消息体，可能是不完整的）。
     */
    private byte[] buf = new byte[0];

    /**
     * 有效字节数。
     */
    private int len;

    /**
     * 喂入一段字节，把其中凑齐的消息逐条吐出来。
     *
     * @param data   数据缓冲
     * @param offset 起始位置
     * @param length 长度
     * @param handler 消息回调
     */
    public void feed(byte[] data, int offset, int length, MessageHandler handler) {
        if (length <= 0) {
            return;
        }
        append(data, offset, length);
        int pos = 0;
        while (len - pos >= GrpcFrame.HEADER_LENGTH) {
            int messageLength = GrpcFrame.decodeLength(buf, pos);
            if (messageLength > GrpcFrame.MAX_MESSAGE_SIZE) {
                throw new GrpcException("gRPC 消息长度 " + messageLength + " 超过上限 " + GrpcFrame.MAX_MESSAGE_SIZE);
            }
            if (len - pos < GrpcFrame.HEADER_LENGTH + messageLength) {
                // 消息体没收全 —— 等下一块
                break;
            }
            if (GrpcFrame.isCompressed(buf, pos)) {
                throw new GrpcException("暂不支持压缩的 gRPC 消息（grpc-encoding 只支持 identity）");
            }
            byte[] payload = new byte[messageLength];
            System.arraycopy(buf, pos + GrpcFrame.HEADER_LENGTH, payload, 0, messageLength);
            pos += GrpcFrame.HEADER_LENGTH + messageLength;
            handler.onMessage(payload);
        }
        if (pos > 0) {
            System.arraycopy(buf, pos, buf, 0, len - pos);
            len -= pos;
        }
    }

    private void append(byte[] data, int offset, int length) {
        if (len + length > buf.length) {
            int cap = buf.length == 0 ? 256 : buf.length;
            while (cap < len + length) {
                cap <<= 1;
            }
            byte[] nb = new byte[cap];
            System.arraycopy(buf, 0, nb, 0, len);
            buf = nb;
        }
        System.arraycopy(data, offset, buf, len, length);
        len += length;
    }

    /**
     * 消息回调。
     */
    public interface MessageHandler {
        /**
         * 收到一条完整的消息。
         *
         * @param payload 消息体（不含 5 字节帧头）
         */
        void onMessage(byte[] payload);
    }
}
