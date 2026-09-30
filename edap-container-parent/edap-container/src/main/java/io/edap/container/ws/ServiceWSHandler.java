package io.edap.container.ws;

import io.edap.container.Container;
import io.edap.http.WSConnection;
import io.edap.http.WSHandler;
import io.edap.json.Eson;
import io.edap.json.JsonObject;
import io.edap.json.JsonObjectImpl;
import io.edap.log.Logger;
import io.edap.log.LoggerManager;
import io.edap.mw.context.RequestContext;
import io.edap.mw.context.RequestContextHolder;
import io.edap.mw.context.UserResolver;
import io.edap.nio.util.NetUtil;

import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

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
 * <p><b>生命周期</b>：per-Container × per-path 单例（由 {@link Container#getOrCreateWsHandler(String)}
 *     创建并缓存），跨 app 版本共享同一实例 → 老 WS 连接（{@code HttpServerNioSession.wsHandler}
 *     握手时钉住）下一条消息靠 msgHandlers 被 rebind 感知新版本。
 *
 * <p><b>WSAuthenticator 不在此处</b>：握手鉴权在 {@code HttpServerNioSession.handeshake} 阶段
 *     从 {@code PathInfo.wsAuthenticator} 取（per-path 1:1 绑定），不在连接级 handler 上重复持有。</p>
 */
public class ServiceWSHandler implements WSHandler {

    private static final Logger log = LoggerManager.getLogger(ServiceWSHandler.class);

    private final UserResolver userResolver;

    /**
     * appId → 该 app 的 method → handler 表。跨 app 共享同一个 ServiceWSHandler 实例（per-path
     * 单例），不同 app 的 method 表按 appId 二级分片，互不干扰。
     *
     * <p>写入语义：
     * <ul>
     *   <li>{@link #rebindMsgHandlers}：按 appId 整张替换该 app 的 method 表（同 app 跨版本切换）
     *   <li>{@link #unbindAppMsgHandlers}：移除该 app 整个 method 表（AppContext.stop / undeploy）
     * </ul>
     *
     * <p>合并视图 {@link #msgHandlers} 在每次写入后通过 {@link #rebuildMerged} 重建，volatile publish
     *     让 dispatch 路径 reader 要么看到旧版本要么看到新版本。</p>
     */
    private final ConcurrentHashMap<String, Map<String, WSServiceMsgHandler<?>>> appMsgHandlers =
            new ConcurrentHashMap<>();

    /**
     * 合并视图：所有 app 的 method 表 union，dispatch 路径读这个（{@code onMessage}）。
     * volatile publish 保证 reader 要么看到旧版本要么看到新版本。
     */
    private volatile Map<String, WSServiceMsgHandler<?>> msgHandlers = Collections.emptyMap();

    public ServiceWSHandler(Container container) {
        // "jwtUserResolver" 是 Container.attach() 阶段 register 的框架默认 bean
        // （参见 Container.registerBuiltinBeans），不依赖任何 app —— 所以 wsHandler
        // 提到 Container 单例后仍能从 containerBeans() 取。
        this.userResolver = (UserResolver) container.containerBeans().getBean("jwtUserResolver");
    }

    // ─────────── 连接生命周期 ───────────

    @Override
    public void onOpen(WSConnection webSocket) {
        if (webSocket == null) return;
        webSocket.clearSessionContext();
        // principal 在 handeshake 阶段已写入 sessionContext（per-path WSAuthenticator 完成）；
        // onOpen 阶段可直接从 sessionContext 取，或异步加载用户信息。
        UserResolver.ResolverResult userResult = userResolver.resolve(webSocket.getHttpRequest());
        if (userResult != null && userResult.isSuccess()) {
            RequestContext rc = userResult.getRequestContext();
            rc.ip(NetUtil.getRemoteAddress(webSocket.getSocketChannel()));
            rc.ua("");
            webSocket.setSessionContext("loginInfo", rc);
        }
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
        log.info("msg:{}", l -> l.arg(message));
        if (ws == null || message == null) return;
        String msgId = "";
        RequestContextHolder.clear();
        try {
            JsonObject json = Eson.parseJsonObject(message);
            String method = json.getString("method");
            msgId = json.getString("id");                         // 缺字段默认 0
            JsonObject payload = json.getJsonObject("params");

            WSServiceMsgHandler<?> handler = msgHandlers.get(method);
            if (handler == null) {
                sendError(ws, msgId, 404, "method not found: " + method);
                return;
            }

            try {
                Object requestContextObj = ws.getSessionContext("loginInfo");
                if (requestContextObj != null && requestContextObj instanceof RequestContext) {
                    RequestContextHolder.set((RequestContext) requestContextObj);
                }
                // wildcard capture：msgHandlers 的 handler 是 WSServiceMsgHandler<?>，取出的实例
                // 类型变量绑定为具体 ?；这里 payload 已知是 JsonObject，handler 的 T 也是
                // JsonObject（WsHandlerGenerator 固定生成 WSServiceMsgHandler<Object> 实现，
                // handle 内部 cast JsonObject → 业务 POJO），直接调 handle 走 volatile map 的
                // 同 method handler 是同一 Class 实例。
                @SuppressWarnings({"rawtypes", "unchecked"})
                Object result = ((WSServiceMsgHandler) handler).handle(payload);
                sendOk(ws, msgId, result);
            } catch (Throwable biz) {
                final String methodFinal = method;
                String msgIdFinal = msgId;
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
        sendError(ws, "", 501, "protobuf not implemented yet");
    }

    @Override
    public void onPing(WSConnection ws, io.edap.http.ws.Ping ping) {
        WSHandler.log.info("WS ping received");
        ws.sendFrame(WSHandler.PONG);
    }

    // ─────────── method 表版本切换 ───────────

    /**
     * 按 appId 整张替换该 app 的 method 表（rebind）。
     *
     * <p>语义：
     * <ul>
     *   <li>同 appId（version 切换）→ 新 method 表覆盖旧 method 表（同 app 内 method 名不会撞）
     *   <li>跨 appId → 检测 method 名冲突，新表 method 名 vs 已注册其他 app 的 method 名 → 重名
     *       抛 {@link IllegalStateException}（fail-fast，业务方需改 method 名 / 错开 path）
     *   <li>newMap 空 → 摘除该 appId 的 method 表
     * </ul>
     *
     * <p>调用方：{@code Container.rebindCurrentWsHandlers}（deploy / switchVersion / undeploy /
     *     restoreToSlot / 启动恢复 末尾），持 appLock 串行化。
     *
     * <p>原子：{@code synchronized} 块内做"检测+写入+rebuildMerged"，reader 通过
     *     {@link #msgHandlers} volatile 字段读合并视图，要么看到旧版本要么看到新版本。</p>
     */
    public void rebindMsgHandlers(String appId, Map<String, WSServiceMsgHandler<?>> newMap) {
        if (appId == null) throw new IllegalArgumentException("appId 不能为空");
        Map<String, WSServiceMsgHandler<?>> toPut;
        if (newMap == null || newMap.isEmpty()) {
            synchronized (this) {
                appMsgHandlers.remove(appId);
                rebuildMerged();
            }
            return;
        }
        // 跨 app method 名冲突检测（fail-fast）。同 appId 整张覆盖不检测（v1/v2 切换语义）。
        synchronized (this) {
            for (Map.Entry<String, Map<String, WSServiceMsgHandler<?>>> e : appMsgHandlers.entrySet()) {
                if (e.getKey().equals(appId)) continue;       // 同 app 跳过（同 app 跨版本切换整张覆盖）
                for (String m : newMap.keySet()) {
                    if (e.getValue().containsKey(m)) {
                        throw new IllegalStateException(
                                "WS method [" + m + "] already registered by appId=" + e.getKey()
                                        + ", cannot register for appId=" + appId
                                        + "（同一 Container 内 WS method 需唯一）");
                    }
                }
            }
            toPut = new HashMap<>(newMap);                    // 复制防共享
            appMsgHandlers.put(appId, toPut);
            rebuildMerged();
        }
    }

    /**
     * 摘除指定 appId 的 method 表（{@link AppContext#stop} / undeploy 路径调用）。
     */
    public void unbindAppMsgHandlers(String appId) {
        if (appId == null) return;
        synchronized (this) {
            if (appMsgHandlers.remove(appId) != null) {
                rebuildMerged();
            }
        }
    }

    /**
     * 重建 {@link #msgHandlers} 合并视图。{@code synchronized} 块内调用。
     */
    private void rebuildMerged() {
        Map<String, WSServiceMsgHandler<?>> merged = new HashMap<>();
        for (Map<String, WSServiceMsgHandler<?>> appMap : appMsgHandlers.values()) {
            merged.putAll(appMap);
        }
        this.msgHandlers = merged;                                // volatile publish
    }

    public Map<String, WSServiceMsgHandler<?>> msgHandlers() {
        return msgHandlers;
    }

    // ─────────── 响应辅助方法 ───────────

    private void sendOk(WSConnection ws, String msgId, Object payload) {
        JsonObject resp = new JsonObjectImpl();
        resp.put("code", 0);
        resp.put("msg", "ok");
        resp.put("id", msgId);
        resp.put("data", payload);                                   // payload 已是 Map/List/基础类型
        String respJson = Eson.toJsonString(resp);
        log.info("resp msg:{}", l -> l.arg(respJson));
        ws.sendText(respJson);
    }

    private void sendError(WSConnection ws, String msgId, int code, String msg) {
        JsonObject resp = new JsonObjectImpl();
        resp.put("code", code);
        resp.put("msg", msg);
        resp.put("id", msgId);
        String respJson = Eson.toJsonString(resp);
        log.info("resp msg:{}", l -> l.arg(respJson));
        ws.sendText(respJson);
    }

    private static String remoteAddrSafe(WSConnection ws) {
        try {
            return ws.getHttpRequest() == null ? "?" : ws.getHttpRequest().getClientAddr();
        } catch (Exception e) {
            return "?";
        }
    }
}
