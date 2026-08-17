# Edap / Container / WebSocket Handler 详细设计

> 本文档定义 edap 容器层 WebSocket 接入的完整协议、接口、组件协作与版本切换策略。
>
> **目标读者**：在 `edap-container-parent` / `edap-http-parent` / `edap-protocol-parent` 模块下做 WS 接入改动的开发者。
>
> **前置文档**：[`CONTAINER_APPCONTEXT_DESIGN.md`](./CONTAINER_APPCONTEXT_DESIGN.md) 定义了 Container / AppContext / RouterHub 三层架构与 in-flight 请求版本切换安全，本文是该架构在 WS 协议上的具体落地。

---

## 一、目标与范围

### 1.1 设计目标

1. **协议层和业务层彻底解耦**。业务开发者只写 `T → R` 的纯函数，不感知 WS 协议细节
2. **跟 HTTP 走同一套注册 + 版本切换链路**。rebind 策略与 `RouterHub.setHandlers` 对称
3. **路径固定为 `/ws`**，跨 proto service 共享一个 `ServiceWSHandler` 实例，method 名带 service 前缀消歧
4. **第一期只支持 JSON**，protobuf wire 格式预留给第二期

### 1.2 不在本文档范围内

- WS 帧编解码（`WebsocketDecoder` / `WebsocketEncoder`）—— 由 `edap-http-core` 负责
- HTTP/1.1 Upgrade 协议握手实现细节 —— RFC 6455，框架内已实现
- 推送 / 订阅 / 服务端主动通知 —— 第一期不做
- 业务自定义异常 code 透传 —— 第一期统一 500

### 1.3 三层组件边界

| 层级 | 接口 | 角色 | 数量 |
|---|---|---|---|
| 连接事件层 | `io.edap.http.WSHandler` | 握手 / onOpen / onMessage / onClose | 1 个（path 唯一） |
| 消息处理层 | `io.edap.container.ws.WSServiceMsgHandler<T>` | 业务逻辑 `T → R` | 每 `@ProtoWebSocket` 方法 1 个（ASM 生成） |
| 握手鉴权层 | `io.edap.http.ws.WSAuthenticator`（新增） | 验 token / 写 principal 到 sessionContext | 每 websocket path 1 个（绑定到 `PathInfo.wsAuthenticator`） |

```
WS 客户端
   │  Upgrade: websocket
   │  Authorization / token
   ▼
HttpServerNioSession.handeshake
   │ 1. 协议校验（Upgrade/Connection/Sec-WebSocket-Key/Version 13）
   │ 2. WSAuthenticator.verify(request)
   │    ├─ 失败 → 401 + 拒绝升级
   │    └─ 成功 → 写 principal → 协议升级
   ▼
ServiceWSHandler.onOpen(session)
   │  日志 + 异步加载用户信息
   ▼
[连接建立] 客户端可开始发消息
   │
   ▼
ServiceWSHandler.onMessage(session, text)
   │ 1. 解析 JSON → {method, msgId, payload}
   │ 2. 查 method 表 → WSServiceMsgHandler<?>
   │ 3. handler.handle(payload) → 业务返回
   │ 4. 包装 {code:0, msg:"ok", msgId, payload} → sendMessage
   ▼
WS 客户端收到响应
```

---

## 二、消息协议

### 2.1 请求

```json
{
  "method": "ReviewService.list",
  "msgId": 42,
  "payload": { ... }
}
```

| 字段 | 必填 | 说明 |
|---|---|---|
| `method` | 是 | 格式 `{InterfaceSimpleName}.{methodName}`，例 `ReviewService.list` |
| `msgId` | 否 | 客户端请求序列号，响应原样回带；缺失时响应 `msgId=0` |
| `payload` | 否 | 业务参数；无参业务可省略 |

### 2.2 响应

成功：

```json
{
  "code": 0,
  "msg": "ok",
  "msgId": 42,
  "payload": { ... }
}
```

异常：

```json
{
  "code": 500,
  "msg": "internal error",
  "msgId": 42
}
```

### 2.3 异常码表

| code | 触发场景 | msg | 连接状态 |
|---|---|---|---|
| 0 | 业务成功 | `"ok"` | 保持 |
| 400 | JSON 解析失败 / 必填字段缺失 | `"bad request"` | 保持 |
| 404 | `method` 不在 method 表里 | `"method not found: {method}"` | 保持 |
| 500 | 业务 handler 抛 Exception | `"internal error"`（生产） | 保持 |

**核心约束**：任何异常**不断开连接**。客户端可继续发下一条。

---

## 三、注解与命名规则

### 3.1 `@ProtoWebSocket` 形态

```java
@Target({ ElementType.METHOD })
@Retention(RetentionPolicy.RUNTIME)
public @interface ProtoWebSocket {
    String method();              // 必填，业务消息 method 名
    String path() default "/ws";  // 第一期固定使用默认值，不允许自定义
}
```

### 3.2 method 命名规则

**强制约定**：`{InterfaceSimpleName}.{methodName}`

例：

```java
public interface ReviewService {
    @ProtoWebSocket(method = "review")
    void review(ReviewMsg req);    // → method = "ReviewService.review"

    @ProtoWebSocket(method = "list")
    List<Review> list(ListReq req); // → method = "ReviewService.list"
}
```

| 优点 | 说明 |
|---|---|
| 跨 service 不冲突 | `ReviewService.review` vs `CommentService.review` 天然区分 |
| 客户端易读 | method 名直接定位 service |
| 日志可追溯 | 异常堆栈直接体现调用方 |

### 3.3 path 固定 `/ws`

第一期 `@ProtoWebSocket.path()` 默认 `/ws` 不允许自定义。理由：

- 所有 WS 客户端连同一个入口，连接管理简单
- 一个 `ServiceWSHandler` 实例跨 proto service 共享
- method 名带 service 前缀天然消歧，跨 service 共存不需要强制独占 path

### 3.4 冲突检测（fail-fast）

**单次部署内** method 名重复 → 启动期抛 `IllegalStateException`，整个 EAR 不上线。

```text
EAR myapp.ear 扫描发现
  ReviewService.review
  ReviewService.review       ← 重复（同一 EAR 内方法命名重复 / proto 生成碰撞）
→ IllegalStateException("EAR myapp duplicate @ProtoWebSocket method: ReviewService.review")
```

跨 EAR 升级同名 method（`ReviewService.review` v1 → v2）**不视为冲突**，属于正常升级行为。

---

## 四、`ServiceWSHandler` 设计

### 4.1 定位

`ServiceWSHandler` 是 edap 容器层对 `io.edap.http.WSHandler` 的**唯一实现**，是 WS 协议层与业务层之间的连接级外壳，承担：

- 连接生命周期管理（onOpen / onClose）
- 消息 method 字段二次路由
- 业务异常捕获 + 标准化响应包装
- 跨版本 method 表的统一管理（rebind 整张替换）

### 4.2 字段

```java
public class ServiceWSHandler implements WSHandler {

    /** 握手鉴权接口，由 Container 启动时注入（HttpServer 也持有同一实例）。 */
    private final WSAuthenticator authenticator;

    /** AppContext 引用，用于 onOpen 阶段异步加载用户信息 / 拿 bean。 */
    private final AppContext appContext;

    /** 该 handler 挂载的 path，第一期固定 "/ws"。 */
    private final String path = "/ws";

    /**
     * method → 业务 handler 映射表。
     *
     * 并发安全：{@link ConcurrentHashMap}（热路径 ~5ns 读）。
     *
     * 替换语义：每次 deploy / switchVersion 由 AppContext.generateAndBindRoutes
     * 整张替换为"当前激活版本"的完整 method 表（与 RouterHub.setHandlers 对称）。
     *
     * volatile 引用 + 不可变 Map 发布：reader 要么看到旧版本要么看到新版本，
     * 不会看到「Map 部分更新」中间态。
     */
    private volatile Map<String, WSServiceMsgHandler<?>> msgHandlers = Collections.emptyMap();
}
```

### 4.3 关键方法

#### 4.3.1 构造

```java
public ServiceWSHandler(WSAuthenticator authenticator, AppContext appContext) {
    this.authenticator = authenticator;
    this.appContext = appContext;
}
```

**全 Container 单例**。path 唯一就是 `/ws`，不分版本。Container 启动期 new 一次，通过 `AppContext.serviceWSHandler()` 暴露。

#### 4.3.2 `onOpen`

```java
@Override
public void onOpen(WSConnection ws) {
    Principal principal = (Principal) ws.getSessionContext("principal");
    if (principal == null) {
        log.warn("onOpen without principal — handeshake path anomaly");
        return;
    }
    log.info("client {} connected as {}", ws.getRemoteAddr(), principal.userId());

    // 异步加载用户信息，不阻塞 onOpen
    userService.loadAsync(principal.userId()).thenAccept(user ->
        ws.setSessionContext("user", user)
    );
}
```

**鉴权不在这里做**。握手鉴权在 `HttpServerNioSession.handeshake` 阶段（协议升级前），onOpen 阶段 principal 已经在 sessionContext 里。

#### 4.3.3 `onMessage(String)`

```java
@Override
public void onMessage(WSConnection ws, String message) {
    int msgId = 0;
    try {
        JsonObject json = Eson.parseJsonObject(message);
        String method = json.getString("method");
        msgId = json.getIntValue("msgId");  // 缺字段默认 0
        JsonObject payload = json.getJsonObject("payload");

        WSServiceMsgHandler<?> handler = msgHandlers.get(method);
        if (handler == null) {
            sendError(ws, msgId, 404, "method not found: " + method);
            return;
        }

        try {
            Object result = handler.handle(payload);
            sendOk(ws, msgId, result);
        } catch (Throwable biz) {
            log.warn("WS biz error: method={}, msgId={}", method, msgId, biz);
            sendError(ws, msgId, 500, "internal error");
        }
    } catch (Exception parseErr) {
        log.warn("WS parse error: {}", parseErr.getMessage());
        sendError(ws, msgId, 400, "bad request");
    }
}
```

#### 4.3.4 `onMessage(byte[])`

```java
@Override
public void onMessage(WSConnection ws, byte[] bytes) {
    // 第一期空实现。第二期实现 protobuf wire 解码：
    // 1. 解 field#1 (bytes method)
    // 2. 解 field#2 (varint msgId)
    // 3. 解 field#3 (bytes payload)
    sendError(ws, 0, 501, "protobuf not implemented yet");
}
```

#### 4.3.5 `rebindMsgHandlers`

```java
/**
 * 整张替换 method 表。调用方：AppContext.generateAndBindRoutes（部署期持 appLock 串行）。
 *
 * 原子：volatile store，reader 要么看到旧版本要么看到新版本。
 *
 * @param newMap 当前激活版本的完整 method 表；null 视为空映射（清空）。
 */
public void rebindMsgHandlers(Map<String, WSServiceMsgHandler<?>> newMap) {
    this.msgHandlers = newMap == null ? Collections.emptyMap() : newMap;
}
```

#### 4.3.6 响应辅助方法

```java
private void sendOk(WSConnection ws, int msgId, Object payload) {
    JsonObject resp = new JsonObjectImpl();
    resp.put("code", 0);
    resp.put("msg", "ok");
    resp.put("msgId", msgId);
    resp.put("payload", payload);  // payload 已是 Map/List/基础类型，Eson.toJsonString 直接序列化
    ws.sendMessage(Eson.toJsonString(resp));
}

private void sendError(WSConnection ws, int msgId, int code, String msg) {
    JsonObject resp = new JsonObjectImpl();
    resp.put("code", code);
    resp.put("msg", msg);
    resp.put("msgId", msgId);
    ws.sendMessage(Eson.toJsonString(resp));
}
```

### 4.4 method 表版本切换策略

#### 4.4.1 整张替换（rebind）

每次 deploy / switchVersion / 启动恢复，`AppContext.generateAndBindRoutes` 一次性算出"当前激活版本"的完整 method 表：

```text
v1 active:
  ReviewService.list   → oldHandler (持有 v1 bean)
  ReviewService.review → oldHandler

v2 deploy：
  RebindTable:
    ReviewService.list      → newHandler (持有 v2 bean)
    ReviewService.review    → newHandler
  → serviceWSHandler.rebindMsgHandlers(rebindTable)
```

**reb 整张替换后**：

| 时点 | method 表内容 | 客户端发 `ReviewService.review` |
|---|---|---|
| rebind 之前 | v1 entry（oldHandler） | 调 oldHandler，v1 bean |
| rebind 那一刻 | volatile load 看到 v1 或 v2 | 调对应版本 handler |
| rebind 之后 | v2 entry（newHandler） | 调 newHandler，v2 bean |

#### 4.4.2 in-flight 消息安全

- rebind 是 volatile publish，atomic 切换
- onMessage 是同步函数，进入即持有表引用至结束
- 同一时刻在派发的消息，要么走老 handler（持有老 bean），要么走新 handler（持有新 bean），不会半截切换
- 老 bean 实例不会被新版本 GC 回收（`RouterHub.wsHandlers` 持有 `WSServiceMsgHandler<?>` 引用，`WSServiceMsgHandler` 持有 bean 引用，整条引用链稳定）

#### 4.4.3 方法删除语义

v2 删了 `ReviewService.review`，新 method 表不含该 entry：

- 老 WS 客户端发 `ReviewService.review` → 查不到 handler → 回 `code:404`
- 客户端可继续发其他 method 名

符合 [`feedback_edap_version_conflict.md`] 立定的规则：老客户端调新 API 失败是正常逻辑，不做版本兼容。

---

## 五、`WSServiceMsgHandler<T>` 与 ASM 生成

### 5.1 接口形态

```java
package io.edap.container.ws;

public interface WSServiceMsgHandler<T> {
    /**
     * 处理一条 WS 消息。
     * @param msg payload（Eson 解码后的 JsonObject；缺失时为 null）；ASM 生成器内部读 JsonObject 字段转 POJO 再调业务
     * @return 业务返回值，序列化为响应 payload；返回 null 时响应 payload 字段为 null
     * @throws Throwable 业务异常会被 ServiceWSHandler 捕获并包装为 code:500
     */
    Object handle(T msg);
}
```

注：第一期 `T = JsonObject`（`JsonObject` 是 `Map<String,Object>`，可直接当 payload 容器使用；缺失时 `json.getJsonObject("payload")` 返回 null）。ASM 生成器内部走 `Eson.parseObject(Eson.toJsonString(jsonObject), paramType)` 把 JsonObject 二次反序列化为业务 POJO，避免 handler 业务方法感知 JSON 库类型。后续若引入 protobuf wire 可改成 `T = byte[]` 或 `T = MessageLite`。

### 5.2 ASM 生成

由 `WSHandlerGenerator`（新增，位于 `io.edap.container.app.asm`）按方法元数据生成字节码，骨架与 `HttpHandlerGenerator` 对称：

```java
public class WSHandlerGenerator {
    public byte[] generate(Class<?> protoIf, Method method, Object bean) {
        // 1. 创建 ClassWriter
        // 2. 继承 Object，实现 WSServiceMsgHandler<JsonObject>
        // 3. <init> 持有 bean 引用（GETSTATIC bean）
        // 4. handle(JsonObject msg) 字节码：
        //    - msg → 业务 POJO 反序列化（Eson.parseObject(Eson.toJsonString(msg), paramType)）
        //    - bean.method(pojo) invokevirtual
        //    - 业务返回原样 return（由 ServiceWSHandler.sendOk 统一序列化）
        //    - return Object
    }
}
```

生成产物由 `AppContext.generateAndBindRoutes` 在部署期一次性实例化并注册进 `ServiceWSHandler.rebindMsgHandlers`。

---

## 六、`WSAuthenticator` 设计（新增）

### 6.1 动机

`io.edap.http.WSHandler.tokenVerify(String token)` 存在两个问题：

1. **耦合到具体 token 字段**，应用必须实现（即使是 demo 也得 override）
2. **接口签名只接 token**，无法读 Authorization header / Cookie / 多来源 token

将鉴权抽成独立的 `WSAuthenticator` 接口，让 `ServiceWSHandler` 与具体鉴权策略解耦。

### 6.2 接口定义

```java
package io.edap.http.ws;

public interface WSAuthenticator {
    /**
     * 握手阶段鉴权。
     * @param request 握手 HTTP 请求（含所有 header / query / cookie）
     * @return 鉴权结果：成功带 principal，失败带 status + reason
     */
    AuthResult verify(HttpRequest request);
}

public class AuthResult {
    private final boolean ok;
    private final int status;          // 401 / 403 / 400
    private final String reason;       // 写入 response body
    private final Principal principal; // ok 时非空，挂到 sessionContext

    // builder / getters ...
}
```

### 6.3 在 `HttpServerNioSession.handeshake` 的接入

```java
private void handeshake(HttpRequest request, HttpResponse resp) throws IOException {
    // 1. 协议校验（Upgrade/Connection/Sec-WebSocket-Key/Version 13）
    // ... 现状代码 ...

    // 2. 业务鉴权（per-path WSAuthenticator，从 PathInfo 取）
    PathInfo pathInfo = request.getPathInfo();        // 已经在 handle() 阶段填好
    WSAuthenticator auth = pathInfo.getWsAuthenticator();  // 必有值：AppContext beans → fallback Container.beans 默认
    AuthResult r = auth.verify(request);
    if (!r.ok) {
        resp.setSimpleResponse(r.status, r.reason);
        return;
    }
    session.setSessionContext("principal", r.principal);

    // 3. 协议升级 + 触发 onOpen
    resp.setSimpleResponse(101, headers, HeaderConnection.UPGRADE, UPGRADE_WEBSOCKET);
    upgraded = true;
    this.httpRequest = request;
    wsHandler.onOpen(this);
}
```

> 注：`auth` 直接从 `PathInfo.wsAuthenticator` 取 —— 与 `pathInfo.getWsHandler()` / `pathInfo.getHttpHandlers()` 平级，符合"`WSAuthenticator` 与 WS path 一对一"的语义。

### 6.4 注入方式：Container 级 bean 容器 + fallback

`WSAuthenticator` 由 edap 框架提供一个默认实现 `HeaderTokenAuthenticator`，注册到 **Container 级 bean 容器**。应用可选择：

- 不提供 `WSAuthenticator` bean → 自动 fallback 到 edap 默认实现（开箱即用）
- 提供 `WSAuthenticator` bean → **覆盖** edap 默认实现（哪怕应用 bean 不标 `@Primary`）

#### 6.4.1 BeanContainer 双层架构

edap 容器内 bean 容器分两层：

| 层级 | 持有者 | 装什么 | 生命周期 |
|---|---|---|---|
| AppContext.beans | AppContext（per-app） | 应用提供的 bean（per-version） | AppContext start/stop |
| Container.beans | Container（per-node） | edap 框架默认 bean | Container 启动期一次性注册，节点退出时销毁 |

`BeanContainer` 类复用，构造器新增 `Container container` 参数：

- `container != null` → 这是 AppContext 级 BeanContainer，查询时 fallback 到 `Container.beans`
- `container == null` → 这是 Container 级 BeanContainer，自己就是 fallback 终点

```java
public BeanContainer(AppContext appContext, Container container,
                     Environment env, EventPublisher events, ShardRegistry shards) {
    this.container = container;  // null = Container 级
    ...
}
```

#### 6.4.2 Container 启动期注册默认 bean

```java
public Container(File appsDir) {
    this(appsDir, parseDefaultCapabilities());
}

public Container(File appsDir, Set<Capability> capabilities) {
    ...
    this.containerBeans = new BeanContainer(null, null, null, null, null);
    registerContainerDefaults();
    initContainerBeans();
}

private void registerContainerDefaults() {
    // edap 框架默认 bean：不标识 @Primary
    containerBeans.register(
        BeanDef.singleton("__edap_default_ws_authenticator",
            HeaderTokenAuthenticator.class, Scope.SINGLETON));
    // 后续：其他框架默认实现也在这里注册
}

private void initContainerBeans() {
    containerBeans.transitionToCommitting();
    for (BeanDef def : containerBeans.topologicalSort()) {
        Object inst = containerBeans.instantiate(def);
        containerBeans.injectDependencies(def, inst);
        containerBeans.invokeInit(def, inst);
        containerBeans.registerInstance(def, inst);
    }
    containerBeans.transitionToReady();
}

public BeanContainer beans() { return containerBeans; }
```

#### 6.4.3 BeanContainer.beanWrapByType fallback 逻辑

```java
public BeanWrap beanWrapByType(Class<?> type) {
    // 1. 查 AppContext 级 byType（应用 bean）
    List<BeanWrap> list = byType.get(type);
    if (list != null && !list.isEmpty()) {
        // 命中任何候选 → 走现有 @Primary 消歧逻辑，不再 fallback
        return resolveFromList(type, list);
    }

    // 2. AppContext 没注册该 type → fallback 到 Container.beans
    if (container != null) {
        BeanWrap fromContainer = container.beans().beanWrapByType(type);
        if (fromContainer != null) return fromContainer;
    }

    // 3. 都没找到
    throw new NoSuchBeanException(type);
}
```

**核心规则**：

> 同一类型的 bean，AppContext.beans 命中任何候选（不管是否标 `@Primary`）→ 不再查 Container 级。AppContext.beans.byType 完全为空（应用未注册该类型任何 bean）→ 才 fallback 到 Container.beans。

#### 6.4.4 覆盖规则总结

| 应用 bean 情况 | 行为 |
|---|---|
| 应用未注册 `WSAuthenticator` bean | AppContext byType 空 → fallback → 用 edap 默认 `HeaderTokenAuthenticator` |
| 应用注册了 1 个 `WSAuthenticator` bean（无 `@Primary`） | AppContext byType 命中 1 个 → 返回应用 bean，**Container 默认 bean 永远查不到** |
| 应用注册了多个 `WSAuthenticator` 候选 + 1 个 `@Primary` | 返回 `@Primary` 那个，Container 默认 bean 不生效 |
| 应用注册了多个 `WSAuthenticator` 候选 + 0 个 `@Primary` | **抛 `NoUniqueBeanException`**（应用内部歧义由应用解决，Container 默认 bean 不参与消歧） |
| 应用注册了多个 `WSAuthenticator` 候选 + 多个 `@Primary` | **抛 `NoUniqueBeanException`** |

**关键语义**：应用 bean 自动覆盖 edap 默认 bean，**不需要标 `@Primary`**（只要单候选；多候选无 `@Primary` 仍然抛异常，符合 edap 既有规则）。

#### 6.4.5 @Inject 字段注入走同一路径

`@Inject WSAuthenticator auth` 字段注入最终走 `beanWrapByType`，自动享受 fallback 语义：

- 应用代码 `@Inject WSAuthenticator auth` → 拿到应用 bean（如果存在）
- 应用代码无 `@Inject WSAuthenticator` 但 `Container.beans` 有 edap 默认 bean → 应用代码通过 `container.beans().getBean(WSAuthenticator.class)` 拿默认 bean

#### 6.4.6 WSAuthenticator 绑定到 PathInfo（per-path 一对一）

`WSAuthenticator` 与 WS path 是**一对一**关系：每个 WS path 的 PathInfo 持有自己的 `WSAuthenticator` 实例。HttpServer 完全不感知 `WSAuthenticator`，WSAuthenticator 通过 `PathInfo.wsAuthenticator` 字段承载，跟 `PathInfo.wsHandler` / `PathInfo.httpHandlers` 平级。

**核心改动**：`PathInfo` 新增一个字段。

```java
public class PathInfo {
    HttpHandler[] httpHandlers;
    WSHandler     wsHandler;

    /** 新增：per-path WSAuthenticator。由 Container 在注册 WS path 时填入。 */
    WSAuthenticator wsAuthenticator;

    public WSAuthenticator getWsAuthenticator() { return wsAuthenticator; }
    public void setWsAuthenticator(WSAuthenticator a) { this.wsAuthenticator = a; }
}
```

`HttpServer` **不需要新增任何字段**——path → PathInfo 路由机制已天然支持在 PathInfo 上挂任意属性。

##### 6.4.6.1 path 来源：`@ProtoWebSocket.path`

WSAuthenticator 的 path **就是** 该 app 注册的 proto service `@ProtoWebSocket.path()`。应用不需要单独的 `@WSAuthPath` 注解，path 由 proto service 注解统一提供。

应用用法：

```java
// proto service 接口（path = "/ws"）
public interface ReviewService {
    @ProtoWebSocket(method = "review", path = "/ws")
    void review(ReviewMsg req);
}

// WSAuthenticator bean（无任何 path 注解——path 由 proto service 提供）
public class AppWSAuthenticator implements WSAuthenticator {
    @Override
    public AuthResult verify(HttpRequest request) {
        // 鉴权逻辑，与 path 无关
    }
}
```

`AppContext.generateAndBindRoutes` 阶段扫描 proto service → 收集所有 `@ProtoWebSocket.path()` → 对每个 path：

1. 拿该 app 的 `WSAuthenticator` bean（`appContext.beans().getBean(WSAuthenticator.class)`，自动 fallback 到 `Container.beans` 的 `HeaderTokenAuthenticator`）
2. 把 bean 挂到该 path 的 `PathInfo.wsAuthenticator`
3. 把 `ServiceWSHandler` 挂到 `PathInfo.wsHandler`
4. 注册 path 到 `HttpServer.httpMapping`

##### 6.4.6.2 WSAuthenticator 与 WS path 一对一绑定

每个 WS path 独立持有自己的 `WSAuthenticator` 绑定（`PathInfo.wsAuthenticator` 字段），与 `PathInfo.wsHandler` 平级。**没有"app 级共享 WSAuthenticator"概念**——每个 path 都有自己的引用，跟 wsHandler 一样是 path 级别的元数据。

**bean 解析**（`AppContext.generateAndBindRoutes` 阶段，对每个 WS path 独立做）：

```java
WSAuthenticator auth = appContext.beans().beanWrapByType(WSAuthenticator.class).instance();
wsPi.setWsAuthenticator(auth);
```

`beanWrapByType` 自动 fallback 到 `Container.beans` 默认 `HeaderTokenAuthenticator`，所以"开箱即用"语义保留，但**绑定的位置是当前 path 的 PathInfo.wsAuthenticator，不是某个全局 app 字段**。

| 应用 `WSAuthenticator` bean 情况 | 各 path 绑定的实例 |
|---|---|
| 应用未提供 `WSAuthenticator` bean | 每 path fallback 到 `Container.beans` → edap 默认 `HeaderTokenAuthenticator`（同一个默认 bean 实例被该 app 所有 path 引用） |
| 应用提供 1 个 `WSAuthenticator` bean | 每 path 绑到该应用 bean（同一个实例被该 app 所有 path 引用，自动覆盖默认实现，无需 `@Primary`） |
| 应用提供多个 `WSAuthenticator` 候选无 `@Primary` | 抛 `NoUniqueBeanException`（应用内部歧义由应用解决） |
| 应用提供多个候选 + 1 个 `@Primary` | 用 `@Primary` 那个，每 path 都绑它 |

**第一期**：path 唯一 `/ws`，等同于"应用 bean（默认 or 自定义）替换默认 bean"。
**未来多 path**：每个 path 独立持有自己的 `WSAuthenticator` 引用；同 app 多 path 共享同一个应用 bean 实例是 byType 查找的自然结果（不是强制共享）。如需 per-path 差异化策略，由 `WSAuthenticator.verify()` 内部按 `request.getPathInfo().getPath()` 分支处理，或升级为按 path 选择 bean 的新机制。

##### 6.4.6.3 WS path 跨 AppContext 冲突检测

**为什么需要独立的冲突检测**：HTTP 路径冲突由 ProtoService FQCN 唯一性间接挡住（`Container.checkAndRegisterIfs`，详见 `Container.java:954-972`），不同 appId 不能持有同 FQCN。但 WS path 是字符串粒度，**不同 FQCN 的两个 proto service 完全可能标同一个 `@ProtoWebSocket(path="/ws")`**——FQCN 检测挡不住 WS path 冲突。

###### 6.4.6.3.1 冲突检测规则

WS path 在同一 Container 内按 appId 唯一：

| 场景 | 行为 |
|---|---|
| 当前 app 想注册的 path 未被任何 app 占用 | 直接注册 |
| 当前 app 想注册的 path **已被同一 appId** 占用 | **允许覆盖**（version 切换语义） |
| 当前 app 想注册的 path **已被不同 appId** 占用 | **抛异常，deploy 失败** |

###### 6.4.6.3.2 实现位置：Container 层

复用已有的 `HttpServer.setHttpMapping(Map<FastBufDataRange, PathInfo>)` 做整张替换。Container 维护两个内部表：

```java
public class Container {
    /**
     * WS path → owner appId。
     * 用于跨 app 冲突检测：同 path 不允许被两个不同 appId 注册。
     */
    private final ConcurrentHashMap<String, String> wsPathOwners = new ConcurrentHashMap<>();

    /**
     * appId → 该 app 贡献的 path 表。
     * 每次 deploy/undeploy 重建 combined map 时按 app 合并。
     */
    private final ConcurrentHashMap<String, Map<FastBufDataRange, PathInfo>> appPathTables = new ConcurrentHashMap<>();

    /**
     * 部署 / version 切换：把 app 的全量 path 表挂上 HttpServer。
     * 调用方：AppContext.generateAndBindRoutes。
     *
     * 流程：
     *   1. 对 newTable 中所有 PathInfo.wsHandler != null 的 entry 做跨 app WS path 冲突检测
     *   2. wsPathOwners 写 owner
     *   3. appPathTables.put(appId, newTable)
     *   4. 合并所有 app 的 table → httpServer.setHttpMapping(combined)
     *
     * @throws IllegalStateException 跨 app WS path 冲突
     */
    public void deployAppRoutes(String appId, Map<FastBufDataRange, PathInfo> newTable) {
        // 1. 冲突检测（同 appId 覆盖放行；不同 appId 抛异常）
        for (Map.Entry<FastBufDataRange, PathInfo> e : newTable.entrySet()) {
            PathInfo pi = e.getValue();
            if (pi.getWsHandler() != null) {
                String pathStr = pi.getPath();  // PathInfo 持有原始 path 字符串供冲突检测
                String prevOwner = wsPathOwners.putIfAbsent(pathStr, appId);
                if (prevOwner != null && !prevOwner.equals(appId)) {
                    throw new IllegalStateException(
                        "WS path [" + pathStr + "] already owned by appId=" + prevOwner
                        + ", cannot register for appId=" + appId
                        + "（同一 Container 内 WS path 需唯一）");
                }
            }
        }
        // 2. 存表 + 3. 合并 + 4. 整张替换
        appPathTables.put(appId, newTable);
        httpServer.setHttpMapping(mergeAllAppTables());
    }

    /**
     * undeploy：摘除 app 贡献的 path 表。
     * 调用方：AppContext.stop。
     */
    public void undeployAppRoutes(String appId) {
        Map<FastBufDataRange, PathInfo> oldTable = appPathTables.remove(appId);
        if (oldTable != null) {
            for (PathInfo pi : oldTable.values()) {
                if (pi.getWsHandler() != null) {
                    wsPathOwners.remove(pi.getPath(), appId);
                }
            }
        }
        httpServer.setHttpMapping(mergeAllAppTables());
    }

    private Map<FastBufDataRange, PathInfo> mergeAllAppTables() {
        Map<FastBufDataRange, PathInfo> combined = new HashMap<>();
        for (Map<FastBufDataRange, PathInfo> t : appPathTables.values()) {
            combined.putAll(t);
        }
        return combined;
    }
}
```

**没有单 path 增删 API**。HttpServer 保持原样（已有 `setHttpMapping` 整张替换 API），不新增 `registerPath(String, PathInfo)`：
- deploy / version 切换：app 整张表替换 → Container 合并 → 整张写入 HttpServer
- undeploy：app 摘表 → Container 重建 combined → 整张写入 HttpServer
- "单 WS path 增删" 不存在独立场景，强行拆 API 反而引入并发复杂度

###### 6.4.6.3.3 与 HTTP FQCN 冲突检测的对比

| 维度 | HTTP（`checkAndRegisterIfs`） | WS（`deployAppRoutes` 内嵌冲突检测） |
|---|---|---|
| 检测粒度 | ProtoService FQCN（间接挡 path） | WS path 字符串（直接挡） |
| 检测时机 | deploy / switchVersion 入口 | `deployAppRoutes` 入口（app 全量 path 表合并前） |
| 跨 app 冲突 | 抛 409 | 抛 `IllegalStateException`，deploy 失败 |
| 同 appId 覆盖 | 允许（version 切换） | 允许（version 切换，putIfAbsent 同 appId 放行） |
| 实现模块 | Container | Container |
| 触发 EAR 扫描阶段 | EAR DeployMetaData 解析后 | AppContext proto service 扫描后 |

**设计对称性**：两种检测都在 Container 层（不在 HttpServer 层），都按 appId 粒度判定冲突，都允许同 appId version 切换覆盖。

###### 6.4.6.3.4 时序

```
AppContext.generateAndBindRoutes 流程：
  1. 扫 proto service @ProtoWebSocket 方法，按 path() 分组
  2. 扫 HTTP route（已有逻辑）
  3. 对每个 WS path：
       ├─ 拿 app 的 WSAuthenticator bean（byType，BeanContainer fallback 自动生效）
       └─ 创建 PathInfo，setWsHandler(serviceWSHandler) + setWsAuthenticator(auth)
  4. 构建 app 全量 pathTable（HTTP + WS entries）
  5. container.deployAppRoutes(appId, pathTable)
       ├─ 跨 app WS path 冲突检测 → 抛 IllegalStateException → deploy 失败
       └─ 整张合并 + httpServer.setHttpMapping(combined)
```

###### 6.4.6.3.5 undeploy 清理

`AppContext.stop` 末尾调 `container.undeployAppRoutes(appId)`：
- 从 `appPathTables` 摘除该 app 的全量表
- 从 `wsPathOwners` 摘除该 app 拥有的所有 WS path
- 重建 combined map → `httpServer.setHttpMapping(combined)`

避免"已 undeploy 的 app 占着 path 不让别的 app 用"。

#### 6.4.7 扩展性：edap 内置功能通过 Container.beans 注入

`Container.beans` + fallback 机制给 edap 容器提供一个**统一的内置功能注入通道**，不依赖 SPI、不污染应用 classpath、不要求应用主动声明依赖。

##### 6.4.7.1 未来可注册的内置功能（示例）

| 内置功能 | 注册到 Container.beans 的 bean | 应用覆盖方式 |
|---|---|---|
| WS 握手鉴权 | `HeaderTokenAuthenticator`（第一期） | 注册自己的 `WSAuthenticator` bean（自动 fallback → 替换默认实现，应用 bean 绑到该 app 的每个 WS path 的 `PathInfo.wsAuthenticator`） |
| HTTP filter / interceptor | `DefaultHttpFilterChain` | 注册自己的 `HttpFilter` |
| 限流 / 熔断 | `DefaultRateLimiter` | 注册自己的 `RateLimiter` |
| 分布式追踪 | `DefaultTracer` | 注册自己的 `Tracer` |
| 配置中心 | `PropsBasedConfigSource` | 注册自己的 `ConfigSource` |
| 指标采集 | `DefaultMetricsCollector` | 注册自己的 `MetricsCollector` |
| 事件总线 | `EventPublisher`（已存在，迁移进来） | 注册自己的 `EventPublisher` |

##### 6.4.7.2 三条核心保证

**保证 1：应用零配置即用内置功能**

应用什么都不写，edap 框架已经把默认实现装进 `Container.beans`。业务代码 `@Inject` 字段 / `getBean(type)` 走 fallback 自动拿到。符合"开箱即用"原则。

**保证 2：应用覆盖不影响 edap 默认功能**

应用写自己的实现 → `AppContext.beans` 命中 → 返回应用 bean，edap 默认 bean 永远不被查到。无需 `@Primary` 标记、单候选即可生效。多候选无 `@Primary` 仍按 edap 既有规则抛 `NoUniqueBeanException`，**应用内部的歧义由应用自己解决，不波及 Container.beans**。

**保证 3：edap 新增内置功能不破坏现有应用**

新增内置功能 = 在 `Container.beans` 注册一个新 bean。已有应用 EAR 完全无感知：
- 不依赖新 bean → 行为不变
- 想用新 bean → 直接 `@Inject`，无需修改应用代码结构（不需要新增 `@Primary` 标记、不需要处理多 bean 歧义）

##### 6.4.7.3 与 SPI 注入的对比

| 维度 | SPI（`ServiceLoader`） | Container.beans + fallback（本文） |
|---|---|---|
| 应用覆盖方式 | 改 classpath / 重新打包 jar | 同名 bean 覆盖（容器装配） |
| 应用代码修改 | 零 | 零 |
| 多实现并存 | 取第一个（classpath 顺序） | `@Primary` 消歧 / 明确异常 |
| 框架依赖注入能力 | 无（SPI 实例无法注入依赖） | 有（bean 可 `@Inject` AppContext / 其他 bean） |
| 调试可见性 | 差（隐藏加载） | 高（启动日志可见） |
| 与 BeanContainer 体系关系 | 割裂（两套 bean 来源） | 统一（Container.beans + AppContext.beans） |
| 多应用隔离 | 困难（classpath 共享） | 自然（AppContext per-app） |

##### 6.4.7.4 演进路径

后续新增内置功能的标准流程：

1. 在 `edap-http-core` / `edap-container` 模块定义接口（如 `RateLimiter`）
2. 在同模块写一个默认实现（如 `DefaultRateLimiter`）
3. 在 `Container.registerContainerDefaults()` 加一行 `register(BeanDef.singleton(...))`
4. 应用代码 `@Inject RateLimiter limiter` 即生效

**完全不需要**改 `BeanContainer`、不需要改 `AppContext`、不需要新增任何注入协议 —— Container.beans + fallback 已经是通用通道。

---

## 七、AppContext 集成

### 7.1 新增字段

```java
public class AppContext {
    /**
     * 全 Container 单例 ServiceWSHandler。path 唯一 /ws，跨版本复用。
     */
    private ServiceWSHandler serviceWSHandler;

    /**
     * AppContext 创建的 BeanContainer 持有 Container 引用，
     * 启用 beanWrapByType fallback 到 Container.beans（见 §6.4）。
     */
    private final Container container;

    public ServiceWSHandler serviceWSHandler() { return serviceWSHandler; }
}
```

### 7.2 `generateAndBindRoutes` 新增 WS 分支

```java
public void generateAndBindRoutes() {
    // 1. 收集当前激活版本的所有 EAR 的 proto service
    List<Class<?>> protoIfs = collectProtoInterfaces();

    // 2. 准备当前版本的完整 method 表
    Map<String, WSServiceMsgHandler<?>> table = new HashMap<>();

    for (Class<?> protoIf : protoIfs) {
        for (Method m : protoIf.getMethods()) {
            ProtoWebSocket ann = m.getAnnotation(ProtoWebSocket.class);
            if (ann == null) continue;

            String method = protoIf.getSimpleName() + "." + m.getName();

            // 单次部署内 fail-fast：重复 method 名直接报错
            if (table.containsKey(method)) {
                throw new IllegalStateException("duplicate @ProtoWebSocket method: " + method);
            }

            Object bean = beanFor(protoIf);   // appContext.beans().beanWrapByType(protoIf).instance()
            WSServiceMsgHandler<?> msgH = wsHandlerGenerator.generate(protoIf, m, bean);
            table.put(method, msgH);
        }
    }

    // 3. 整张替换 ServiceWSHandler method 表
    serviceWSHandler.rebindMsgHandlers(table);

    // 4. 构建 app 全量 pathTable（HTTP + WS entries）并交给 Container
    Map<FastBufDataRange, PathInfo> pathTable = new HashMap<>();
    // ... 填入 HTTP path entries（既有逻辑）...
    PathInfo wsPi = new PathInfo();
    wsPi.setFound(true);
    wsPi.setPath("/ws");
    wsPi.setWsHandler(serviceWSHandler);
    wsPi.setWsAuthenticator(appContext.beans().beanWrapByType(WSAuthenticator.class).instance());
    pathTable.put(httpPathKey("/ws"), wsPi);

    container.deployAppRoutes(appId, pathTable);  // 跨 app 冲突检测 + 整张替换 HttpServer
}
```

### 7.3 与 HTTP rebind 的对称性

| 维度 | HTTP (RouterHub) | WS (ServiceWSHandler) |
|---|---|---|
| 数据结构 | `List<HttpHandler>` × 4 份 | `Map<String, WSServiceMsgHandler<?>>` × 1 份 |
| 替换入口 | `RouterHub.setHandlers(...)` | `ServiceWSHandler.rebindMsgHandlers(...)` |
| 替换时机 | 部署期持 `appLock` 串行 | 同上 |
| in-flight 安全 | 老请求走老 handler 完整返回 | 老消息走老 handler 完整返回 |
| 跨版本 in-flight | 老连接保留，handler 不替换 | 老连接保留，handler 不替换 |
| unbindAll | `clear()` 4 份 List | **不动**（method 表跨版本复用） |

> **关键差异**：ServiceWSHandler **不参与 unbindAll**。method 表跨版本复用，因为 path 唯一 `/ws`，WS 长连接必须跨版本保持不断。

---

## 八、协作时序

### 8.1 启动期

```text
Bootstrap.main
  └─ Edap.run()
       └─ Container.bootstrap()
            ├─ Container 构造：注册框架默认 bean（HeaderTokenAuthenticator 等）→ containerBeans READY
            ├─ HttpServer 初始化（PathInfoMatcher / PathDecoder）
            ├─ AppContext.load(apps/*.ear)
            │    ├─ FQCN 冲突检测（Container.checkAndRegisterIfs）→ 跨 app 抛 409
            │    ├─ AppContext.beans 创建 + COMMITTING + READY
            │    └─ AppContext.generateAndBindRoutes()
            │         ├─ 扫 proto service @ProtoWebSocket 方法，按 path() 分组
            │         ├─ 拿该 app 的 WSAuthenticator bean（byType，BeanContainer fallback 自动生效）
            │         ├─ ASM 生成 WSServiceMsgHandler 实例
            │         ├─ 算当前版本完整 method 表
            │         ├─ serviceWSHandler.rebindMsgHandlers(table)
            │         ├─ 构建 app 全量 pathTable（HTTP + WS entries）
            │         │    ├─ WS path: 创建 PathInfo，setWsHandler + setWsAuthenticator
            │         │    └─ HTTP paths: 既有逻辑
            │         └─ container.deployAppRoutes(appId, pathTable)
            │              ├─ WS path 跨 app 冲突检测 → 抛 IllegalStateException → deploy 失败
            │              └─ 合并所有 app 的 table → httpServer.setHttpMapping(combined)
            └─ httpServer.setHttpMapping(...)
```

> **关键澄清**：
> - `WSAuthenticator` 在 **AppContext.generateAndBindRoutes** 阶段从 `appContext.beans()` 取（自动 fallback 到 `Container.beans` 的 `HeaderTokenAuthenticator`）
> - 应用 bean **不需要 `@WSAuthPath` 注解**：path 直接来自 `@ProtoWebSocket.path()`
> - `PathInfo.wsAuthenticator` 在构建 app pathTable 时由 AppContext 填入，跟 `PathInfo.wsHandler` 平级
> - **跨 app 冲突检测**在 `Container.deployAppRoutes` 入口（不允许两个不同 appId 占用同 WS path）
> - `HttpServer` 完全不感知 `WSAuthenticator`，只用 `PathInfo.wsAuthenticator` 字段承载
> - **HTTP + WS path 同 app 一起部署**：`deployAppRoutes` 接 app 全量 pathTable（含 HTTP + WS entries），无单独注册 WS path 的 API

### 8.2 WS 连接建立

```text
客户端
  │  GET /ws HTTP/1.1
  │  Upgrade: websocket
  │  Connection: Upgrade
  │  Sec-WebSocket-Key: ...
  │  Sec-WebSocket-Version: 13
  │  Authorization: Bearer xxx    (或 Cookie / Sec-WebSocket-Protocol)
  ▼
HttpServerNioSession.handeshake
  ├─ 协议校验：Upgrade/Connection/Sec-WebSocket-Key/Version 13 → OK
  ├─ WSAuthenticator.verify(request)
  │    ├─ 失败 → 401 + return（不升级）
  │    └─ 成功 → sessionContext("principal", p)
  ├─ 协议升级响应 101 + Sec-WebSocket-Accept
  └─ wsHandler.onOpen(session)
       ├─ 取 principal → log
       └─ userService.loadAsync(principal.userId()).thenAccept(...)  // 异步
```

### 8.3 WS 消息往返

```text
客户端
  │  {"method":"ReviewService.list","msgId":42,"payload":{...}}
  ▼
HttpServerNioSession.decode (upgraded=true 分支)
  └─ WebsocketDecoder → TEXT_OPCODE → wsHandler.onMessage(session, text)
       └─ ServiceWSHandler.onMessage
            ├─ parse JSON → {method="ReviewService.list", msgId=42, payload=...}
            ├─ handler = msgHandlers.get("ReviewService.list")
            ├─ handler.handle(payload)
            │    └─ WSServiceMsgHandler (ASM) 字节码：
            │         ├─ payload → Review POJO 反序列化
            │         ├─ reviewService.list(pojo) invokevirtual
            │         └─ List<Review> → JSON 序列化
            ├─ result = [Review, Review, ...]
            └─ sendOk(ws, 42, result)
                 └─ wsConnection.sendMessage({"code":0,"msg":"ok","msgId":42,"payload":[...]})
  ▼
客户端收到响应
```

### 8.4 版本切换期间

```text
T0  v1 active，method 表 = {ReviewService.list → oldHandler, ...}
    老 WS 连接在派发 v1 消息（onMessage 同步执行中）
    新部署请求进来，Container 持 appLock

T1  AppContext.generateAndBindRoutes 启动
    ├─ 扫新版本 proto service @ProtoWebSocket 方法
    ├─ 生成 newHandler 实例
    └─ serviceWSHandler.rebindMsgHandlers(newTable)
         └─ volatile store publish

T2  老 onMessage 完成（持有 v1 handler 引用至函数返回）
    ├─ 业务返回 → 包装响应 → wsConnection.sendMessage(...)

T3  新连接 onMessage 进入
    ├─ 看到 volatile load 后的 newTable
    └─ 走 newHandler（持有 v2 bean）

T4  老 WS 连接发 "ReviewService.list" 消息
    ├─ 走 newHandler（持有 v2 bean）—— 升级成功
    └─ 走 method 不存在（如果 v2 删了该方法）→ 回 code:404
```

---

## 九、错误处理

### 9.1 异常分类与处理

| 异常源 | 捕获点 | 响应 code | msg | 日志级别 |
|---|---|---|---|---|
| JSON 解析失败 | `onMessage(String)` try/catch | 400 | "bad request" | WARN |
| `method` 缺失或 null | `onMessage(String)` try/catch | 400 | "bad request" | WARN |
| `method` 不在表里 | `onMessage(String)` 内 if | 404 | "method not found: xxx" | INFO |
| 业务 handler 抛 Exception | `onMessage(String)` 内 try/catch | 500 | "internal error" | WARN（带堆栈） |
| 序列化响应失败 | `sendOk` / `sendError` 内 | 不发响应 | —— | ERROR |
| `sendMessage` 抛 IOException | 同上 | 不发响应 | —— | ERROR |

### 9.2 关键约束

1. **任何异常不断开连接**。客户端可继续发下一条。
2. **异常不污染 method 表**。method 表替换是原子的（volatile publish），异常只在单次 onMessage 内传播。
3. **响应失败不影响后续消息**。`sendMessage` 失败只记日志，连接保持。

---

## 十、关键变更清单

落地时的文件变更，按依赖顺序：

### 10.1 edap-http-core 模块（接口层）

| # | 改动 | 文件 |
|---|---|---|
| 1 | 新增 `WSAuthenticator` 接口 + `AuthResult` + `Principal` | `io.edap.http.ws` 包（新） |
| 2 | 新增 `HeaderTokenAuthenticator`（占位 stub，第一期只做"读取 Authorization header → 返回 ok"） | `io.edap.http.ws` 包（新） |
| 3 | `WSConnection.sendMessage(String)` 确认存在 / 新增 | `WSConnection.java` |

### 10.2 edap-http-server 模块

| # | 改动 | 文件 |
|---|---|---|
| 4 | `HttpServerNioSession.handeshake` 改造（从 `pathInfo.getWsAuthenticator()` 取 per-path authenticator，失败返 r.status/r.reason） | `HttpServerNioSession.java` |

> **HttpServer 无改动**：复用已有 `setHttpMapping(Map<FastBufDataRange, PathInfo>)` 整张替换 API，无需新增 `registerPath(String, PathInfo)`。

### 10.3 edap-container 模块（BeanContainer 双层架构 + WS 集成）

| # | 改动 | 文件 |
|---|---|---|
| 5 | `BeanContainer` 构造器新增 `Container container` 参数（null = Container 级） | `BeanContainer.java` |
| 6 | `BeanContainer.beanWrapByType` 新增 fallback 到 `Container.beans` 的逻辑 | `BeanContainer.java` |
| 7 | `Container` 持有 `BeanContainer containerBeans` 实例，构造期注册框架默认 bean | `Container.java` |
| 8 | `AppContext` 创建 `BeanContainer` 时传 container 引用 | `AppContext.java` |
| 9 | `PathInfo` 新增 `wsAuthenticator` 字段 + `path` 字段（per-path WSAuthenticator 挂载点 + 跨 app 冲突检测 key） | `PathInfo.java` |
| 10 | `ServiceWSHandler` 重构（构造接 appContext；onMessage 真正派发；rebindMsgHandlers 整张替换） | `ServiceWSHandler.java` |
| 11 | `AppContext` 新增 `serviceWSHandler()` 字段 + WS 扫描分支 + 构建 app 全量 pathTable（HTTP + WS entries） + 调用 `container.deployAppRoutes(appId, pathTable)` | `AppContext.java` |
| 12 | `Container.deployAppRoutes` / `undeployAppRoutes`（app 全量 pathTable 合并 + 跨 app WS path 冲突检测 + wsPathOwners / appPathTables 表管理） | `Container.java` |
| 13 | `WSHandlerGenerator`（ASM） | `edap-container.app.asm` 包（新） |

### 10.4 edap-protobuf-wire 模块

| # | 改动 | 文件 |
|---|---|---|
| 14 | `@ProtoWebSocket` javadoc 补充 method 命名规则 + path 固定 `/ws` 约定 + "path 同时作为该 app 的 WSAuthenticator 绑定路径" 约定 | `ProtoWebSocket.java` |

### 10.5 edap-container-test 模块

| # | 改动 | 文件 |
|---|---|---|
| 15 | 测试：`WsDemoHandler` 改造为 `ServiceWSHandler` 用例 | `test/handler/WsDemoHandler.java` |

---

## 十一、第一期不做

| 项 | 备注 |
|---|---|
| protobuf wire 编码 | `onMessage(byte[])` 留空，预留接口 |
| 推送 / 订阅 / 服务端主动通知 | 业务 handler 无 `WSConnection` 引用 |
| 业务自定义异常 code 透传 | 统一 500 |
| traceId / version header 字段 | JSON 扁平结构 |
| `@ProtoWebSocket.path()` 自定义 | 第一期固定 `/ws` |
| method 表方法删除检测 | 自然行为，删了的方法下次部署直接消失 |
| 跨版本方法清理 | 单 Container 单版本，无此场景 |
| `Container.wsPathOwners` / `appPathTables` 的 undeploy 清理（owner 表只增不减） | 第一期保留，Container 生命周期跟 JVM 同；同 app 重 deploy 走 `deployAppRoutes` 时，同 appId 覆盖放行（`putIfAbsent` 同 owner 跳过） |
| 真正的多 WS path 鉴权（一个 app 多 path 各挂不同 WSAuthenticator） | 第一期 path 唯一 `/ws`；WSAuthenticator 1:1 绑定到 `PathInfo.wsAuthenticator` 已实现，但只对 `/ws` 一个 path 验证过 |
| `HeaderTokenAuthenticator` 真正鉴权逻辑（验签 / 黑名单 / 过期） | 第一期只做"读取 Authorization header → 返回 ok"，让握手能跑通 |
| `Container.beans` 多个框架默认 bean 之间存在相互依赖时的初始化顺序 | 第一期只注册 `HeaderTokenAuthenticator`，无依赖 |
| `AppContext` 现有 `resolveDependencyByType` 硬编码的 5 个伪 bean 分支（`AppContext` / `Environment` / `EventPublisher` / `RouterHub` / `ShardRegistry`）改成查 `Container.beans` 的通用路径 | 第一期保留硬编码分支，第二期重构 |
