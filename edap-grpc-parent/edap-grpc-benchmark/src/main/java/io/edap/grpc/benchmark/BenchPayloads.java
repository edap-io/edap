/*
 * Copyright 2026 The edap Project
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

package io.edap.grpc.benchmark;

import com.google.protobuf.ByteString;

/**
 * 请求消息的构造 —— edap 侧与 google 侧的 payload 逐字段对齐。
 *
 * <p>两个消息类型不同（edap 是 {@link Payload}，google 是 {@link Bench.Payload}），
 * 但<b>字段编号、字段类型、字段内容完全一样</b>，所以编码出来的字节也完全一样
 * （这一点由 {@code GrpcInteropTest} 断言）。</p>
 */
public final class BenchPayloads {

    private BenchPayloads() {
    }

    /**
     * edap 侧的请求消息。
     *
     * @param blobSize blob 的字节数
     */
    public static Payload edap(int blobSize) {
        Payload payload = new Payload();
        payload.setName(BenchConstants.REQUEST_NAME);
        payload.setValue(BenchConstants.REQUEST_VALUE);
        payload.setBlob(blob(blobSize));
        return payload;
    }

    /**
     * google 侧的请求消息。
     *
     * @param blobSize blob 的字节数
     */
    public static Bench.Payload google(int blobSize) {
        return Bench.Payload.newBuilder()
                .setName(BenchConstants.REQUEST_NAME)
                .setValue(BenchConstants.REQUEST_VALUE)
                .setBlob(ByteString.copyFrom(blob(blobSize)))
                .build();
    }

    /**
     * 构造 blob 的内容 —— 用可预测的字节序列（不是全 0），
     * 避免「压缩/编码器对全 0 有捷径」这类因素干扰对比。
     *
     * <p>公开出来是为了让兼容性测试能用同一套字节去核对两边的回显。</p>
     */
    public static byte[] blob(int size) {
        byte[] data = new byte[size];
        for (int i = 0; i < size; i++) {
            data[i] = (byte) (i & 0xFF);
        }
        return data;
    }
}
