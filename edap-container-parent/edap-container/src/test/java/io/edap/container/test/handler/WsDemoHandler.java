package io.edap.container.test.handler;

import io.edap.container.AppContext;
import io.edap.container.app.asm.AbstractHandler;
import io.edap.container.test.DemoService;
import io.edap.container.test.HelloReq;
import io.edap.container.test.HelloResp;
import io.edap.container.ws.WSServiceMsgHandler;
import io.edap.json.Eson;
import io.edap.json.JsonObject;

/**
 * WS 业务 handler 测试 fixture：模拟 {@code @ProtoWebSocket} 方法生成的 ASM Handler 类形状，
 * 用于在脱离扫描 + ASM 生成链路的环境下，单独验证 {@code ServiceWSHandler} 的 method
 * 路由 + JsonObject 反序列化 + bean 调用 + 响应 JSON 序列化整条链路。
 *
 * <p><b>手工等价物</b>：本类手动书写的形状与 {@code WsHandlerGenerator} 生成的类
 *     （{@code WSServiceMsgHandler<Object> + AbstractHandler} 子类 + 静态 bean 字段 +
 *     {@code Object handle(Object)} 调用 bean 业务方法）一一对应。当 ASM 生成链路
 *     不可用时，可手动 new 一个本类实例塞进 {@code ServiceWSHandler.rebindMsgHandlers}
 *     走同一条 dispatch 路径。</p>
 *
 * <p><b>handle 入参</b>：{@code msg} 是 {@code ServiceWSHandler.onMessage} 解析 JSON
 *     后得到的 {@code JsonObject}（{@code payload} 字段，缺省 null）。</p>
 *
 * <p><b>handle 出参</b>：返回 {@code Object}。本类返回 {@code HelloResp}，调用方
 *     （{@code ServiceWSHandler.sendOk}）会走 {@code Eson.toJsonString} 序列化后写入
 *     响应消息的 payload 字段。</p>
 *
 * <p><b>异常路径</b>：业务抛异常被 {@code ServiceWSHandler.onMessage} 捕获并包装为
 *     {@code code:500} 响应，本类不需要 catch —— 演示完整的异常 → 500 包装链路。</p>
 */
public class WsDemoHandler extends AbstractHandler implements WSServiceMsgHandler<Object> {

    /** DemoService bean（构造期从 AppContext 拉一次；null 表示未注册 → handle 返回 null）。 */
    private static DemoService bean;

    public WsDemoHandler(AppContext appContext) {
        super(appContext);
        Object obj = getBean(DemoService.class);
        if (obj == null) {
            bean = null;
        } else {
            bean = (DemoService) obj;
        }
    }

    /**
     * 业务方法入口。{@code msg} 是 {@code JsonObject}（调用方已从 JSON 消息的
     *     {@code payload} 字段解析出来）。{@code null} payload 走 early-return。</p>
     *
     * <p>当前实现：直接 {@link Eson#toBean(JsonObject, Class)}（{@code Map} → Bean 路径），
     *     与 {@code WsHandlerGenerator} 生成的字节码行为一致；可改为根据 method 签名分派
     *     到不同业务方法（每个 method → 一个 Handler 子类，dispatch 由
     *     {@code ServiceWSHandler.msgHandlers} 维护）。</p>
     */
    @Override
    public Object handle(Object msg) {
        if (msg == null) return null;
        if (!(msg instanceof JsonObject)) {
            throw new IllegalArgumentException("WS payload must be JsonObject, got: "
                    + msg.getClass().getName());
        }
        JsonObject json = (JsonObject) msg;
        HelloReq req = Eson.toBean(json, HelloReq.class);
        return bean == null ? null : bean.hello(req);
    }
}
