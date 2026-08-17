package io.edap.http.ws;

import io.edap.http.HttpRequest;

/**
 * WS 握手阶段鉴权接口（edap 容器层抽象）。
 *
 * <p><b>定位</b>：在 HTTP/1.1 Upgrade 协议握手完成后、协议升级响应发出前调用——
 *     鉴权失败 → 返 4xx + 拒绝升级；鉴权成功 → principal 写入 sessionContext → 协议升级 101。</p>
 *
 * <p><b>设计动机</b>：第一期曾用 {@code WSHandler.tokenVerify(String)} default 方法，签名仅接 token 字符串，
 *     无法读 Authorization header / Cookie / 多来源 token，应用必须 override 才能跑通。现改为独立接口、
 *     接整个 {@link HttpRequest}，鉴权策略可读任意 header / query / cookie，"读 token + 验签 + 构造
 *     {@link Principal}"封装在单一实现内。</p>
 *
 * <p><b>默认实现</b>：edap 框架在 {@code Container.beans} 注册默认实现
 *     {@link HeaderTokenAuthenticator}（仅做"读取 Authorization header → 返回 ok"），
 *     应用可通过注册自己的 {@code WSAuthenticator} bean 自动覆盖（byType fallback 机制）。</p>
 *
 * <p><b>绑定粒度</b>：每个 WS path 独立绑定一个 {@code WSAuthenticator} 实例
 *     （{@code PathInfo.wsAuthenticator} 字段），与 {@code PathInfo.wsHandler} 平级。
 *     不同 path 可持有不同实例；同 app 多 path 共享同一个应用 bean 是 byType 查找的自然结果。</p>
 *
 * <p><b>生命周期</b>：实现类按普通 bean 注入生命周期管理；{@code verify} 方法在 NIO 线程
 *     上调用（每次握手），实现类需保证线程安全 / 无状态 / 短耗时。</p>
 */
public interface WSAuthenticator {

    /**
     * 鉴权握手请求。
     *
     * @param request 握手 HTTP 请求（含所有 header / query / cookie）
     * @return 鉴权结果：成功带 {@link Principal}，失败带 status + reason
     */
    AuthResult verify(HttpRequest request);
}
