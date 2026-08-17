package io.edap.container.ws;

import io.edap.container.AppContext;
import io.edap.http.WSConnection;
import io.edap.http.WSHandler;
import io.edap.json.Eson;
import io.edap.json.JsonObject;
import io.edap.json.JsonObjectImpl;
import io.edap.log.Logger;
import io.edap.log.LoggerManager;

import java.io.IOException;
import java.util.Collections;
import java.util.Map;

/**
 * edap 容器层 {@link WSHandler} 唯一实现：WS 连接级事件 + 业务 method 二次路由。
 *
 * <p><b>职责</b>：
 * <ul>
 *   <li>连接生命周期：{@link #onOpen} / {@link #onClose}（日志 + 异步加载用户信息等扩展点）</li>
 *   <li>消息 method 二次路由：{@link #onMessage} 解析 JSON → 查 {@code msgHandlers} → 调业务 handler → 包装响应</li>
 *   <li>业务异常捕获：包装为 {@code code:500} 标准化响应，<b>不断开连接</b></li>
 *   <li>跨版本 method 表统一管理：{@link #rebindMsgHandlers} 整张替换（与 {@code RouterHub.setHandlers} 对称）</li>
 * </ul>
 *
 * <p><b>生命周期</b>：全 Container 单例（path 唯一 {@code /ws}），跨版本复用。
 *     长连接不随 version 切换断开（与 HTTP rebind 策略对称）。</p>
 *
 * <p><b>WSAuthenticator 不在此处</b>：握手鉴权在 {@code HttpServerNioSession.handeshake} 阶段
 *     从 {@code PathInfo.wsAuthenticator} 取（per-path 1:1 绑定），不在连接级 handler 上重复持有。</p>
 */
public class ServiceWSHandler implements WSHandler {

    private static final Logger log = LoggerManager.getLogger(ServiceWSHandler.class);

    /** AppContext 引用（用于 onOpen 阶段异步加载用户信息 / 拿 bean）。 */
    private final AppContext appContext;

    /**
     * method → 业务 handler 映射表。
     *
     * <p>替换语义：每次 deploy / version 切换由 {@code AppContext.generateAndBindRoutes}
     *     整张替换为"当前激活版本"的完整 method 表。volatile publish 保证 reader 要么看到
     *     旧版本要么看到新版本，in-flight 消息走老 handler 完整返回（老 bean 实例不会被 GC，
     *     整条引用链由本字段 + RouterHub.wsHandlers 稳定持有）。</p>
     */
    private volatile Map<String, WSServiceMsgHandler<?>> msgHandlers = Collections.emptyMap();

    public ServiceWSHandler(AppContext appContext) {
        this.appContext = appContext;
    }

    // ─────────── 连接生命周期 ───────────

    @Override
    public void onOpen(WSConnection webSocket) {
        if (webSocket == null) return;
        webSocket.clearSessionContext();
        // principal 在 handeshake 阶段已写入 sessionContext（per-path WSAuthenticator 完成）；
        // onOpen 阶段可直接从 sessionContext 取，或异步加载用户信息。
        log.info("WS connection opened: {}", l -> l.arg(remoteAddrSafe(webSocket)));
    }

    @Override
    public void onClose(WSConnection webSocket) {
        log.info("WS connection closed: {}", l -> l.arg(remoteAddrSafe(webSocket)));
        try {
            webSocket.getSocketChannel().close();
        } catch (IOException e) {
            log.warn("webSocket.getSocketChannel().close() error", e);
        }
    }

    @Override
    public void onError(WSConnection webSocket, Throwable throwable) {
        log.warn("WS connection error", l -> l.threw(throwable));
    }

    // ─────────── 消息 method 二次路由 ───────────

    @Override
    public void onMessage(WSConnection ws, String message) {
        if (ws == null || message == null) return;
        int msgId = 0;
        try {
            JsonObject json = Eson.parseJsonObject(message);
            String method = json.getString("method");
            msgId = json.getIntValue("msgId");                         // 缺字段默认 0
            JsonObject payload = json.getJsonObject("payload");

            WSServiceMsgHandler<?> handler = msgHandlers.get(method);
            if (handler == null) {
                sendError(ws, msgId, 404, "method not found: " + method);
                return;
            }

            try {
                // wildcard capture：msgHandlers 的 handler 是 WSServiceMsgHandler<?>，取出的实例
                // 类型变量绑定为具体 ?；这里 payload 已知是 JsonObject，handler 的 T 也是
                // JsonObject（WsHandlerGenerator 固定生成 WSServiceMsgHandler<Object> 实现，
                // handle 内部 cast JsonObject → 业务 POJO），直接调 handle 走 volatile map 的
                // 同 method handler 是同一 Class 实例。
                @SuppressWarnings({"rawtypes", "unchecked"})
                Object result = ((WSServiceMsgHandler) handler).handle(payload);
                sendOk(ws, msgId, result);
            } catch (Throwable biz) {
                final int msgIdFinal = msgId;
                final String methodFinal = method;
                log.warn("WS biz error: method={}, msgId={}",
                        l -> l.arg(methodFinal).arg(msgIdFinal).threw(biz));
                sendError(ws, msgId, 500, "internal error");
            }
        } catch (Exception parseErr) {
            log.warn("WS parse error: {}", l -> l.arg(parseErr.getMessage()).threw(parseErr));
            sendError(ws, msgId, 400, "bad request");
        }
    }

    @Override
    public void onMessage(WSConnection ws, byte[] bytes) {
        // 第一期空实现。第二期实现 protobuf wire 解码：
        // 1. 解 field#1 (bytes method)
        // 2. 解 field#2 (varint msgId)
        // 3. 解 field#3 (bytes payload)
        sendError(ws, 0, 501, "protobuf not implemented yet");
    }

    @Override
    public void onPing(WSConnection ws, io.edap.http.ws.Ping ping) {
        WSHandler.log.info("WS ping received");
        ws.sendFrame(WSHandler.PONG);
    }

    // ─────────── method 表版本切换 ───────────

    /**
     * 整张替换 method 表。调用方：{@code AppContext.generateAndBindRoutes}（部署期持 appLock 串行）。
     *
     * <p>原子：volatile store，reader 要么看到旧版本要么看到新版本。null 视为空映射（清空）。</p>
     */
    public void rebindMsgHandlers(Map<String, WSServiceMsgHandler<?>> newMap) {
        this.msgHandlers = newMap == null ? Collections.emptyMap() : newMap;
    }

    public Map<String, WSServiceMsgHandler<?>> msgHandlers() {
        return msgHandlers;
    }

    // ─────────── 响应辅助方法 ───────────

    private void sendOk(WSConnection ws, int msgId, Object payload) {
        JsonObject resp = new JsonObjectImpl();
        resp.put("code", 0);
        resp.put("msg", "ok");
        resp.put("msgId", msgId);
        resp.put("payload", payload);                                   // payload 已是 Map/List/基础类型
        ws.sendText(Eson.toJsonString(resp));
    }

    private void sendError(WSConnection ws, int msgId, int code, String msg) {
        JsonObject resp = new JsonObjectImpl();
        resp.put("code", code);
        resp.put("msg", msg);
        resp.put("msgId", msgId);
        ws.sendText(Eson.toJsonString(resp));
    }

    private static String remoteAddrSafe(WSConnection ws) {
        try {
            return ws.getHttpRequest() == null ? "?" : ws.getHttpRequest().getClientAddr();
        } catch (Exception e) {
            return "?";
        }
    }
}
