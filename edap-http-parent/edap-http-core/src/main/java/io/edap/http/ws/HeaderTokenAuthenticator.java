package io.edap.http.ws;

import io.edap.http.HttpRequest;
import io.edap.log.Logger;
import io.edap.log.LoggerManager;

/**
 * edap 框架默认 {@link WSAuthenticator} 实现（stub）。
 *
 * <p><b>第一期行为</b>：仅从 {@code Authorization} header 读取 token，构造
 *     {@link Principal#Principal(String, String)}（userId = token 字面值），
 *     返回 {@link AuthResult#success}。无任何验签 / 黑名单 / 过期判断。</p>
 *
 * <p><b>为什么是 stub</b>：edap 不做"业务鉴权策略"——真实场景（JWT 验签 / OAuth 流程 /
 *     Session 表查询）由应用层提供自己的 {@link WSAuthenticator} bean 覆盖。框架默认实现
 *     只保证握手能跑通、业务能跑通到 {@code onOpen} 阶段，验证后续链路正确。</p>
 *
 * <p><b>bean 注册</b>：edap 容器在启动期通过 {@code Container.containerBeans} 注册本类
 *     单例（{@code Scope.SINGLETON}，无依赖）。应用可通过注册自己的 {@code WSAuthenticator}
 *     bean 自动覆盖（byType fallback 机制，应用 bean 优先于本默认实现）。</p>
 *
 * <p><b>未来增强</b>：第二期可扩展为 JWT 验签 + 黑名单 + 过期校验（参见
 *     {@code doc/WS_HANDLER_DESIGN.md §11} 第一期不做列表）。</p>
 */
public class HeaderTokenAuthenticator implements WSAuthenticator {

    private static final Logger log = LoggerManager.getLogger(HeaderTokenAuthenticator.class);

    @Override
    public AuthResult verify(HttpRequest request) {
        String token = null;
        try {
            token = request.getHeaderValue("Authorization").getValue();
        } catch (Exception e) {
            // header 缺失 / 解析异常：fallback 到 query parameter ?token=
            try {
                token = request.getParameter("token");
            } catch (Exception ignored) {
                // query parameter 也没拿到 — 仍允许握手跑通，userId 为 null
            }
        }
        if (token == null) {
            token = "anonymous";
        }
        final String finalToken = token;
        log.info("WS handshake: token={}", l -> l.arg(maskToken(finalToken)));
        return AuthResult.success(new Principal(finalToken, finalToken));
    }

    /**
     * 简单掩码日志输出：避免完整 token 写入日志。仅首末各保留 2 字符。
     */
    private static String maskToken(String token) {
        if (token == null || token.length() <= 4) {
            return "***";
        }
        return token.substring(0, 2) + "***" + token.substring(token.length() - 2);
    }
}
