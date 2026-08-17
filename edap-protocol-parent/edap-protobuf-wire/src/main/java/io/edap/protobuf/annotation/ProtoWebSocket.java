package io.edap.protobuf.annotation;

import java.lang.annotation.*;

/**
 * 标注 proto service 接口方法为 WebSocket 业务方法（edap 容器层）。
 *
 * <p><b>method 字段</b>：JSON 消息体 {@code {"method":"...", "msgId":..., "payload":...}} 中的
 *     {@code method} 字符串。{@link #method()} 是该方法对外暴露的"业务方法名"，用于
 *     {@code ServiceWSHandler.onMessage} 按 method 字段二次路由到对应业务 handler。
 *     <b>命名约定</b>：
 *     <ul>
 *       <li>必须与 Java 方法名一致（或业务层另行约定）——保持开发直觉</li>
 *       <li>同一 app 内全局唯一——多 {@code @ProtoWebSocket} 方法不能共享同一 method 字符串，
 *           否则 dispatch 走第一个命中（{@code Map.put} 覆盖语义），旧 handler 被 GC，
 *           旧 bean 实例仍由 RouterHub 持有但 dispatch 路径已断</li>
 *       <li>避免下划线 / 大写字母——客户端拼字符串易错；保持全小写 + 驼峰或下划线分隔</li>
 *     </ul></p>
 *
 * <p><b>path 字段</b>：当前固定为 {@code /ws}（与所有 {@code @ProtoWebSocket} 方法共享）。
 *     客户端连接时握手请求 URL 用 {@code ws://host/ws}；握手成功后，连接进入长连接状态，
 *     后续所有业务消息都走同一连接，<b>不再使用 URL path</b>——path 仅作连接入口标识。
 *     业务 method 路由由 JSON 消息体 {@code method} 字段决定（{@link #method()}）。</p>
 *
 * <p><b>为什么是单一 path（而不是每个 method 一个 path）</b>：WebSocket 是长连接协议，
 *     客户端与服务端建立一次连接后通过帧（frame）持续通信；如果每个 method 一个 path，
 *     客户端需要为每个 method 单独建立连接（连接复用差 + 资源浪费）。edap 容器层选择
 *     "单一 path + 业务 method 二次路由"模型——客户端只连一次，所有 method 共享同一连接。</p>
 *
 * <p><b>与 {@code @ProtoHttp} 的对比</b>：{@code @ProtoHttp} 每个 method 一个 path（HTTP
 *     无连接概念，URL 即路由）；{@code @ProtoWebSocket} 所有 method 共享 {@code /ws} path，
 *     业务路由走 JSON 消息字段。两者路由模型对称但实现不同。</p>
 *
 * <p><b>per-path 鉴权绑定</b>：{@code /ws} 路径绑定一个 {@code WSAuthenticator} 实例
 *     （{@code PathInfo.wsAuthenticator}），握手阶段调用。多 {@code @ProtoWebSocket} 方法
 *     共享同一鉴权器——鉴权粒度是"path 级"而非"method 级"。</p>
 *
 * <p><b>示例</b>：
 * <pre>{@code
 *   @ProtoWebSocket
 *   public class ChatService {
 *       Object sendMsg(JsonObject payload);    // 客户端发送 {"method":"sendMsg", ...}
 *
 *       @ProtoWebSocket(method = "history")
 *       Object getHistory(JsonObject payload);  // 客户端发送 {"method":"history", ...}
 *   }
 * }</pre>
 */
@Target({ ElementType.METHOD })
@Retention(RetentionPolicy.RUNTIME)
@Inherited
@Documented
public @interface ProtoWebSocket {
    /**
     * JSON 消息的 method 字段值（必填）。
     *
     * <p>客户端发送 {@code {"method":"<这里填的值>", "msgId":..., "payload":...}} →
     *     ServiceWSHandler 按此字符串分发到对应业务 handler。同一 app 内全局唯一。</p>
     */
    String method() default "";

    /**
     * WS 连接入口 path。固定 {@code /ws}，所有 {@code @ProtoWebSocket} 方法共享此路径。
     *
     * <p>当前实现下 path 实际不被消费（ServiceWSHandler 单实例 + 单一固定 path）——保留
     *     字段仅为未来扩展（如允许 app 自定义 path，触发多 ServiceWSHandler 实例）。</p>
     *
     * @return 固定 {@code /ws}
     */
    String path() default "/ws";
}
