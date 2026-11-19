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
import io.edap.erpc.ErpcProtocolException;
import io.edap.erpc.ErpcRequest;
import io.edap.erpc.codec.ErpcMetadataCodec;
import io.edap.erpc.frame.ErpcFrameCodec;
import io.edap.erpc.frame.ErpcFrameHeader;
import io.edap.erpc.frame.ErpcFrameType;
import io.edap.nio.ParseResult;
import io.edap.rpc.RpcContext;
import io.edap.rpc.RpcMetadata;
import io.edap.rpc.RpcProtocol;
import io.edap.rpc.RpcTimeout;

import java.util.Arrays;

/**
 * eRPC 服务端的分帧器。
 *
 * <p><b>★ 每连接一个实例</b>：它持有跨 read 的累积缓冲（底层读分发器每次 read 前
 * {@code buf.reset()}，半包必须由上层自己攒），共享会串包。</p>
 *
 * <p>切出来的 {@link ErpcRequest} 里载荷仍是<b>未解码</b>的字节 ——
 * 解码需要业务 DTO 的类型，只有 {@link io.edap.erpc.ErpcMethod} 知道，
 * 所以解码发生在 handler 里。</p>
 *
 * <p><b>★ 帧内容区可能带一段 RPC 上下文</b>：帧头 {@code type} 字节的 bit7
 * （{@link ErpcFrameType#HAS_CONTEXT}）置位时，内容区 =
 * {@code [RPC 上下文段][业务 DTO]}。本类负责把上下文段剥下来（灌进
 * {@link RpcContext#metadata()}），业务 DTO 交给 handler。</p>
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
        request.setFlags(header.getFlags());
        // ★ 内容区带不带 RPC 上下文，由帧头 type 字节的 bit7 决定
        //   （见 ErpcFrameType.HAS_CONTEXT）：带 ⇒ [上下文段][业务 DTO]，
        //   不带 ⇒ [业务 DTO]（老路径，字节级不变）。
        RpcMetadata metadata = null;
        byte[] payload;
        if (ErpcFrameType.hasContext(header.getType())) {
            ContentSegment segment = readContentSegment();
            metadata = segment.metadata;
            payload = segment.dto;
        } else {
            payload = codec.payload();
        }
        request.setPayload(payload);
        request.setContext(buildContext(header, metadata));
        codec.consume();

        result.setFinished(true);
        result.setMessage(request);
        return result;
    }

    @Override
    public void reset() {
        codec.reset();
    }

    // ------------------------------------------------------------------
    // 上下文
    // ------------------------------------------------------------------

    /**
     * 读帧内容区开头的 <b>RPC 上下文段</b>，并切出业务 DTO。
     *
     * <p>★ <b>就地解析</b>：上下文段在累积缓冲上直接解析（不先拷一份），
     * 业务 DTO 只 {@code copyOfRange} <b>拷一次</b> ⇒ 拷贝次数与「不带上下文」时持平。</p>
     *
     * <p>★ <b>长度越界 ⇒ 抛协议错误</b>（不是静默截断）：上下文段的长度字段
     * 是对端给的，信它就会把后面的字节当 DTO 解 ⇒ 直接关连接。</p>
     */
    private ContentSegment readContentSegment() {
        int contentLen = codec.payloadLength();
        if (contentLen < ErpcMetadataCodec.LENGTH_PREFIX) {
            throw new ErpcProtocolException("带 RPC 上下文的帧内容区太短: " + contentLen + " 字节");
        }
        byte[] raw = codec.rawBuffer();
        int contentOff = codec.payloadOffset();
        int blockLen = ErpcMetadataCodec.blockLength(raw, contentOff);
        // ★ 用减法比较而不是加法 —— blockLen 是线上来的 32 位无符号数，
        //   加起来会溢出成负数 ⇒ 越界检查被绕过。
        if (blockLen < 0 || blockLen > contentLen - ErpcMetadataCodec.LENGTH_PREFIX) {
            throw new ErpcProtocolException("RPC 上下文段长度越界: 段长 " + blockLen
                + " 字节, 内容区只有 " + contentLen + " 字节");
        }
        RpcMetadata metadata = ErpcMetadataCodec.decode(raw,
            contentOff + ErpcMetadataCodec.LENGTH_PREFIX, blockLen);
        int dtoOff = contentOff + ErpcMetadataCodec.LENGTH_PREFIX + blockLen;
        byte[] dto = Arrays.copyOfRange(raw, dtoOff, contentOff + contentLen);
        return new ContentSegment(metadata, dto);
    }

    /**
     * 帧内容区拆成两段的结果 —— <b>RPC 上下文段</b> + <b>业务 DTO</b>。
     */
    private static final class ContentSegment {

        /** 上下文段解出来的元数据。 */
        final RpcMetadata metadata;
        /** 业务 DTO 的字节（已从累积缓冲拷出来）。 */
        final byte[] dto;

        ContentSegment(RpcMetadata metadata, byte[] dto) {
            this.metadata = metadata;
            this.dto = dto;
        }
    }

    /**
     * 按帧头建本次调用的上下文。
     *
     * <p>★ <b>线上只传「相对时长」</b>：帧头 {@code flags} 的 bit1~bit7 是
     * 7-bit 超时代码（见 {@link RpcTimeout#fromErpcFlags(int)}），
     * 这里立刻换算成本进程的<b>绝对 nanoTime 时刻</b> ——
     * {@code nanoTime} 跨进程不可比，跨机传绝对时刻没有意义。</p>
     *
     * <p>老客户端写的帧 {@code flags} 恒为 0 ⇒ 解出「无超时」，
     * 行为与改造前完全一致。</p>
     *
     * @param header   帧头
     * @param metadata 帧里带来的元数据；{@code null} ⇒ 本次调用没有元数据
     */
    private static RpcContext buildContext(ErpcFrameHeader header, RpcMetadata metadata) {
        long nanos = RpcTimeout.decodeErpcTimeoutNanos(RpcTimeout.fromErpcFlags(header.getFlags()));
        return RpcContext.builder()
            .protocol(RpcProtocol.ERPC)
            .method("erpc:" + header.getMethodId())
            .requestId(header.getRequestId())
            .traceId(header.getTraceId())
            .deadlineNanos(nanos > 0 ? System.nanoTime() + nanos : 0L)
            .metadata(metadata)
            .build();
    }
}
