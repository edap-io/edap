package io.edap.container.ws;

/**
 * edap 容器层 WS 业务消息处理器接口（functional interface，方法引用友好）。
 *
 * <p>由 {@code io.edap.container.app.asm.WsHandlerGenerator} 按 proto service 方法
 *     元数据生成实现类（{@code WSServiceMsgHandler<JsonObject>} 子类）。每个
 *     {@code @ProtoWebSocket} 标注的接口方法 → 一个 ASM 生成的 Handler 实例。</p>
 *
 * <p><b>handle 签名</b>：{@code Object handle(T msg)}。
 * <ul>
 *   <li>入参 {@code T}：第一期固定 {@code JsonObject}（{@code Eson.parseJsonObject} 解码后的
 *       payload，缺失时为 null）。ASM 字节码内部把 JsonObject 二次反序列化为业务 POJO
 *       （{@code Eson.parseObject(Eson.toJsonString(msg), paramType)}）再调业务方法，
 *       避免业务方法感知 JSON 库类型</li>
 *   <li>返回值 {@code Object}：业务方法返回值原样返回（由 {@code ServiceWSHandler.sendOk}
 *       走 {@code Eson.toJsonString} 序列化），返回 null 时响应 payload 字段为 null</li>
 *   <li>异常 {@code Throwable}：业务异常会被 {@code ServiceWSHandler.onMessage} 捕获并
 *       包装为 {@code code:500} 响应，不中断连接</li>
 * </ul>
 *
 * <p><b>为什么不传 raw {@code String}</b>：避免每个 Handler 自己做 JSON 解析；
 *     也不传 raw {@code byte[]} —— protobuf wire 编码是第二期的事。</p>
 *
 * <p><b>后续扩展</b>：第二期引入 protobuf wire 时，{@code T} 可改为 {@code byte[]} 或
 *     {@code com.google.protobuf.MessageLite}，与当前 JSON 路径并存。</p>
 */
@FunctionalInterface
public interface WSServiceMsgHandler<T> {

    Object handle(T msg);
}
