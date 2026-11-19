/*
 * Copyright 2023 The edap Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at:
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.edap.erpc;

import io.edap.erpc.codec.ErpcCodec;
import io.edap.rpc.RpcChannelContext;
import io.edap.rpc.RpcContext;

/**
 * 一次 eRPC 调用的信封 —— <b>客户端与服务端共用同一个类</b>。
 *
 * <p>★ <b>它不是线格式</b>：线上格式是「32 字节帧头 + 业务 DTO 字节」
 * （见 {@link io.edap.erpc.frame.ErpcFrameHeader}）。本类是<b>进程内</b>的调用上下文。</p>
 *
 * <p>★ <b>两侧各用各的那一半字段</b>（同一个类，不必维护两套几乎一样的类）：</p>
 * <table border="1">
 *   <caption>字段分工</caption>
 *   <tr><th>字段</th><th>客户端（发起方）</th><th>服务端（接收方）</th></tr>
 *   <tr><td>{@code methodId} / {@code serviceId} / {@code traceId}</td>
 *       <td>调用方填</td><td>从帧头解出来</td></tr>
 *   <tr><td>{@code requestId}</td>
 *       <td>传输层分配（连接内唯一）</td><td>从帧头解出来</td></tr>
 *   <tr><td>{@code type} / {@code codec} / {@code flags}</td>
 *       <td>传输层填（帧头字段）</td><td>从帧头解出来</td></tr>
 *   <tr><td>{@code payload} / {@code requestObject}</td>
 *       <td>请求 DTO（字节或对象）</td><td>请求 DTO 的<b>编码字节</b>（未解码）</td></tr>
 *   <tr><td>{@code timeoutMillis}</td>
 *       <td>调用方填（本次超时）</td><td>不用（超时在 {@code flags} 里）</td></tr>
 *   <tr><td>{@code context}</td>
 *       <td>调用级上下文（deadline / 取消 / 元数据）</td><td>同上，由帧头 + 上下文段构造</td></tr>
 *   <tr><td>{@code channelContext}</td>
 *       <td>连接级上下文（选中连接后由传输层填）</td><td>不用（连接级上下文在 session 上）</td></tr>
 *   <tr><td>{@code serializer}</td>
 *       <td>不用</td><td>按帧头 {@code codec} 现场解析出来的编解码器</td></tr>
 * </table>
 *
 * <p>★ <b>载荷为什么保持「未解码」</b>：解码需要业务 DTO 的 {@code Class}，
 * 只有 {@link ErpcMethod} 知道 ⇒ 解码发生在 handler 里。</p>
 *
 * <p>★ <b>客户端侧的载荷为什么可以是对象</b>：客户端把请求 DTO <b>对象</b>交给传输层，
 * 出站写帧时才编码 ⇒ 载荷在网络上只被拷一次（与 gRPC 的「帧头内联」同一形态）。</p>
 */
public class ErpcRequest {

    // ------------------------------------------------------------------
    // 帧头字段（线上协议的一部分）
    // ------------------------------------------------------------------

    /**
     * 帧头里的 type 字节 —— bit7 是「本帧带 RPC 上下文」标志位，bit0~bit6 是帧种类。
     *
     * @see io.edap.erpc.frame.ErpcFrameType
     */
    private int type;
    /** 帧头里的 codec 字节（序列化器 id）。 */
    private int codec;
    /**
     * 帧头里的 flags（bit0 = 压缩预留，bit1~bit7 = 超时代码）。
     *
     * <p>★ <b>原样抄来</b>：帧头里的其它字段都被抄进本对象，唯独 flags 之前被丢了
     * ⇒ 服务端看不到客户端给的超时。</p>
     */
    private int flags;
    /** 帧头里的 methodId —— 服务端靠它路由，不解析载荷。 */
    private int methodId;
    /** 帧头里的 serviceId（观测用）。 */
    private int serviceId;
    /**
     * 帧头里的 requestId —— 响应帧原样回填；客户端据此配对。
     *
     * <p>★ <b>连接内唯一且递增</b>；帧头里就是 32 位 ⇒ 这里也是 {@code int}。</p>
     */
    private int requestId;
    /** 帧头里的 traceId。 */
    private long traceId;

    // ------------------------------------------------------------------
    // 载荷
    // ------------------------------------------------------------------

    /**
     * 业务 DTO 的编码字节。
     *
     * <p>★ 服务端侧它<b>还没解码</b>（解码要业务 DTO 的 Class）；客户端侧它只在
     * 「没有对象、只有字节」的调用路径上被填。</p>
     */
    private byte[] payload;
    /**
     * 业务 DTO <b>对象</b>（还没编码）—— 客户端延迟编码用。
     *
     * <p>为什么要有它：客户端原来在 {@code ErpcClient.invoke} 里就把请求 DTO
     * 编码成 {@code byte[]}，出站时再把这段字节拷进待写缓冲 ⇒ 载荷被拷两次。
     * 改成「客户端只交对象，出站写帧时才编码」⇒ 载荷只拷一次
     * （与 gRPC 的 {@code GrpcFrameEncoder} 同一形态）。</p>
     */
    private Object requestObject;

    // ------------------------------------------------------------------
    // 上下文
    // ------------------------------------------------------------------

    /**
     * 本次调用的 RPC 上下文（deadline / 取消信号 / 属性 / <b>元数据</b>）。
     *
     * <p>★ <b>不上线</b>：客户端真正上线的是帧头 {@code flags} 里的 7-bit 超时代码
     * 与帧内容区里的上下文段。框架在调 handler 前把它绑定到当前线程，
     * 所以 handler 里 {@code RpcContext.current()} 拿到的就是它。</p>
     */
    private RpcContext context;
    /**
     * 本次调用走的那条<b>连接</b>的上下文（连接级状态）。
     *
     * <p>★ <b>客户端</b>：由传输层在<b>选中连接之后</b>填上 ⇒ 客户端拦截器的
     * {@code beforeCall} 阶段（选连接之前）拿不到，{@code afterCall} 阶段能拿到。</p>
     *
     * <p>★ <b>服务端</b>：不用本字段 —— 服务端的连接级上下文在 session 上
     * （{@code ErpcServerSession#channelContext()}）。</p>
     */
    private RpcChannelContext channelContext;
    /**
     * 本帧载荷的编解码器 —— 服务端按帧头 {@code codec} 字节现场解析出来。
     *
     * <p>★ <b>不上线</b>：它不是客户端发来的字段，而是服务端根据帧头解析的结果。
     * handler 靠它解码请求载荷、编码响应载荷。</p>
     */
    private ErpcCodec serializer;

    // ------------------------------------------------------------------
    // 客户端侧
    // ------------------------------------------------------------------

    /**
     * 本次调用的超时（毫秒）。
     *
     * <p>★ <b>客户端侧专用</b>：它只是把超时从调用方带到
     * {@link RpcInvoker} 这条接口上 —— 那条接口的 {@code invoke} 没有超时参数。
     * 服务端看到的超时在帧头 {@code flags} 里。</p>
     */
    private long timeoutMillis;

    // ------------------------------------------------------------------
    // 访问器：帧头字段
    // ------------------------------------------------------------------

    /**
     * 帧头里的 type 字节（bit7 = 本帧带 RPC 上下文）。
     */
    public int getType() {
        return type;
    }

    public void setType(int type) {
        this.type = type;
    }

    public int getCodec() {
        return codec;
    }

    public void setCodec(int codec) {
        this.codec = codec;
    }

    /**
     * 帧头里的 flags（bit0 = 压缩预留，bit1~bit7 = 超时代码）。
     */
    public int getFlags() {
        return flags;
    }

    public void setFlags(int flags) {
        this.flags = flags;
    }

    public int getMethodId() {
        return methodId;
    }

    public void setMethodId(int methodId) {
        this.methodId = methodId;
    }

    public int getServiceId() {
        return serviceId;
    }

    public void setServiceId(int serviceId) {
        this.serviceId = serviceId;
    }

    /**
     * 请求号（连接内唯一）—— 响应帧原样回填。
     */
    public int getRequestId() {
        return requestId;
    }

    public void setRequestId(int requestId) {
        this.requestId = requestId;
    }

    public long getTraceId() {
        return traceId;
    }

    public void setTraceId(long traceId) {
        this.traceId = traceId;
    }

    // ------------------------------------------------------------------
    // 访问器：载荷
    // ------------------------------------------------------------------

    /**
     * 业务 DTO 的编码字节（服务端侧未解码）。
     */
    public byte[] getPayload() {
        return payload;
    }

    public void setPayload(byte[] payload) {
        this.payload = payload;
    }

    /**
     * 业务 DTO <b>对象</b>（还没编码）—— 客户端延迟编码用。
     */
    public Object getRequestObject() {
        return requestObject;
    }

    /**
     * 填业务 DTO <b>对象</b>（延迟到出站写帧时才编码）。
     */
    public void setRequestObject(Object requestObject) {
        this.requestObject = requestObject;
    }

    // ------------------------------------------------------------------
    // 访问器：上下文
    // ------------------------------------------------------------------

    /**
     * 本次调用的 RPC 上下文（服务端：框架在调 handler 前绑定到当前线程）。
     */
    public RpcContext getContext() {
        return context;
    }

    public void setContext(RpcContext context) {
        this.context = context;
    }

    /**
     * 本次调用走的那条连接的上下文（连接级状态）。
     *
     * @return 连接级上下文；<b>选连接之前是 {@code null}</b>
     */
    public RpcChannelContext getChannelContext() {
        return channelContext;
    }

    /**
     * 填本次调用走的那条连接的上下文（由传输层在选中连接后调用）。
     */
    public void setChannelContext(RpcChannelContext channelContext) {
        this.channelContext = channelContext;
    }

    /**
     * 本帧载荷的序列化器（服务端按帧头 codec 字节解析出来的）。
     */
    public ErpcCodec getSerializer() {
        return serializer;
    }

    public void setSerializer(ErpcCodec serializer) {
        this.serializer = serializer;
    }

    // ------------------------------------------------------------------
    // 访问器：客户端侧
    // ------------------------------------------------------------------

    /**
     * 本次调用的超时（毫秒）。
     */
    public long getTimeoutMillis() {
        return timeoutMillis;
    }

    public void setTimeoutMillis(long timeoutMillis) {
        this.timeoutMillis = timeoutMillis;
    }

    @Override
    public String toString() {
        return "ErpcRequest{methodId=" + methodId + ", requestId=" + requestId
            + ", codec=" + codec + ", traceId=" + traceId
            + ", payload=" + (payload == null ? 0 : payload.length) + "B}";
    }
}
