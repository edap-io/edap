/*
 * Copyright 2023 The edap Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package io.edap.erpc;

/**
 * RPC消息的数据类型定义
 *
 * @deprecated 历史骨架，<b>线上协议不再使用</b>。eRPC 的线上格式是
 * 「32 字节帧头 + 业务 DTO 字节」（见 {@link io.edap.erpc.frame.ErpcFrameHeader}）。
 * 本类注释里写的「0 为 eproto，1 为 protobuf」与现在的 codec id 分配
 * （0=protobuf，1=json，见 {@link io.edap.erpc.codec.ErpcCodecIds}）也不一致 ⇒ 保留仅为不破坏已有 import。
 */
@Deprecated
public class RpcMessage {
    /**
     * 协议版本
     */
    private int version;
    /**
     * 压缩算法0为不压缩
     */
    private int compress;
    /**
     * 编解码算法0为eproto，1为protobuf
     */
    private int codec;
    /**
     * 数据类型1为rpc请求，2为rpc的返回
     */
    private int dataType;
    /**
     * 压缩后数据的长度
     */
    private int len;
    /**
     * 压缩后消息体数据
     */
    private byte[] data;
}
