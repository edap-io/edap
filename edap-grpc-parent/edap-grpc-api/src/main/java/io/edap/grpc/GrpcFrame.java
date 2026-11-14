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
 * gRPC 的消息分帧格式（Length-Prefixed-Message）。
 *
 * <pre>
 * +--------+--------+--------+--------+--------+
 * | compressed |         length (4 bytes, big endian)        |
 * +--------+--------+--------+--------+--------+
 * |                     message data ...                    |
 * +--------------------------------------------------------+
 * </pre>
 *
 * <p>{@code compressed} 为 0 表示消息体是未压缩的 protobuf 编码，为 1 表示消息体使用
 * {@code grpc-encoding} 指定的算法压缩。本实现只支持 identity（不压缩），
 * 遇到压缩标志为 1 的帧必须报错。
 */
public final class GrpcFrame {

    /**
     * 帧头的长度：1 字节压缩标志 + 4 字节长度。
     */
    public static final int HEADER_LENGTH = 5;

    /**
     * 单条消息允许的最大长度，默认 4MB，与 grpc-java 的默认值一致。
     */
    public static final int MAX_MESSAGE_SIZE = 4 * 1024 * 1024;

    private GrpcFrame() {
    }

    /**
     * 把一条消息编码成 gRPC 的帧。
     *
     * @param message 消息体，为 null 时编码成一条空消息
     * @return 帧数据
     */
    public static byte[] encode(byte[] message) {
        return encode(message, 0, message == null ? 0 : message.length);
    }

    /**
     * 把消息体的一部分编码成 gRPC 的帧。
     *
     * @param message 消息体
     * @param offset  消息体在数组中的起始位置
     * @param length  消息体的长度
     * @return 帧数据
     */
    public static byte[] encode(byte[] message, int offset, int length) {
        if (length < 0) {
            throw new GrpcException("消息长度不能为负数: " + length);
        }
        byte[] frame = new byte[HEADER_LENGTH + length];
        frame[0] = 0;
        writeLength(frame, 1, length);
        if (length > 0 && message != null) {
            System.arraycopy(message, offset, frame, HEADER_LENGTH, length);
        }
        return frame;
    }

    /**
     * 从帧头中读出消息体的长度。
     *
     * @param data   帧数据
     * @param offset 帧头的起始位置，即压缩标志所在的位置
     * @return 消息体的长度
     */
    public static int decodeLength(byte[] data, int offset) {
        if (data == null || offset < 0 || offset + 5 > data.length) {
            throw new GrpcException("gRPC 帧头不完整，长度=" + (data == null ? 0 : data.length));
        }
        int length = ((data[offset + 1] & 0xFF) << 24)
                | ((data[offset + 2] & 0xFF) << 16)
                | ((data[offset + 3] & 0xFF) << 8)
                | (data[offset + 4] & 0xFF);
        if (length < 0) {
            throw new GrpcException("gRPC 消息长度为负数: " + length);
        }
        return length;
    }

    /**
     * 判断帧中的消息体是否被压缩。
     *
     * @param data   帧数据
     * @param offset 帧头的起始位置
     * @return true 表示消息体被压缩
     */
    public static boolean isCompressed(byte[] data, int offset) {
        if (data == null || offset < 0 || offset >= data.length) {
            throw new GrpcException("gRPC 帧头不完整");
        }
        return (data[offset] & 0xFF) != 0;
    }

    /**
     * 检查消息长度是否超过上限。
     *
     * @param length 消息长度
     */
    public static void checkLength(int length) {
        if (length > MAX_MESSAGE_SIZE) {
            throw new GrpcException("gRPC 消息长度 " + length + " 超过上限 " + MAX_MESSAGE_SIZE);
        }
    }

    /**
     * 把 gRPC 帧头写进缓冲。
     *
     * <p>帧头 = 1 字节压缩标志（0 = identity）+ 4 字节消息体长度。</p>
     *
     * @param buf           目标缓冲
     * @param offset        帧头的起始位置
     * @param payloadLength 消息体长度
     */
    public static void writeHeader(byte[] buf, int offset, int payloadLength) {
        if (payloadLength < 0) {
            throw new GrpcException("gRPC 消息长度不能为负数: " + payloadLength);
        }
        buf[offset] = 0;
        writeLength(buf, offset + 1, payloadLength);
    }

    private static void writeLength(byte[] frame, int offset, int length) {
        frame[offset] = (byte) ((length >>> 24) & 0xFF);
        frame[offset + 1] = (byte) ((length >>> 16) & 0xFF);
        frame[offset + 2] = (byte) ((length >>> 8) & 0xFF);
        frame[offset + 3] = (byte) (length & 0xFF);
    }
}
