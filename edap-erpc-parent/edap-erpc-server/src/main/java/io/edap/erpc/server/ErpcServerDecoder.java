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

package io.edap.erpc.server;

import io.edap.Decoder;
import io.edap.buffer.FastBuf;
import io.edap.erpc.frame.ErpcFrameCodec;
import io.edap.erpc.frame.ErpcFrameHeader;
import io.edap.erpc.frame.ErpcRequest;
import io.edap.nio.ParseResult;

/**
 * eRPC 服务端的分帧器。
 *
 * <p><b>★ 每连接一个实例</b>：它持有跨 read 的累积缓冲（底层读分发器每次 read 前
 * {@code buf.reset()}，半包必须由上层自己攒），共享会串包。</p>
 *
 * <p>切出来的 {@link ErpcRequest} 里载荷仍是<b>未解码</b>的字节 ——
 * 解码需要业务 DTO 的类型，只有 {@link io.edap.erpc.ErpcMethod} 知道，
 * 所以解码发生在 handler 里。</p>
 */
public class ErpcServerDecoder implements Decoder<ErpcRequest, ErpcServerSession> {

    private final ErpcFrameCodec codec = new ErpcFrameCodec();

    @Override
    public ParseResult<ErpcRequest> decode(FastBuf bufIn, ErpcServerSession nioSession) {
        // 累积本次读到的字节（第一次调用有数据，之后 remain()==0 ⇒ 空操作）
        codec.append(bufIn);

        // ★ magic / 版本校验在「帧头一到」就做 —— 不能等整帧收全：
        //   非 eRPC 流量的 length 字段多半是个巨大的数 ⇒ 整帧永远收不全
        //   ⇒ 等整帧才校验的话，这条连接会一直挂着不报错。
        if (codec.hasHeader()) {
            if (!codec.checkMagic()) {
                throw new ErpcProtocolException("帧头 magic 不符 —— 这不是 eRPC 流量");
            }
            if (codec.version() != ErpcFrameHeader.VERSION) {
                throw new ErpcProtocolException("不支持的 eRPC 协议版本: " + codec.version());
            }
        }

        ParseResult<ErpcRequest> result = new ParseResult<>();
        if (!codec.hasFrame()) {
            result.setFinished(false);
            return result;
        }
        ErpcFrameHeader header = codec.header();
        ErpcRequest request = new ErpcRequest();
        request.setType(header.getType());
        request.setCodec(header.getCodec());
        request.setMethodId(header.getMethodId());
        request.setServiceId(header.getServiceId());
        request.setRequestId(header.getRequestId());
        request.setTraceId(header.getTraceId());
        request.setPayload(codec.payload());
        codec.consume();

        result.setFinished(true);
        result.setMessage(request);
        return result;
    }

    @Override
    public void reset() {
        codec.reset();
    }
}
