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

package io.edap.rpc;

/**
 * RPC消息的数据类型定义
 */
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
