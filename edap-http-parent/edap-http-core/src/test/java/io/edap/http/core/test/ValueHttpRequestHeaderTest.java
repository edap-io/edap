package io.edap.http.core.test;

import io.edap.http.HeaderValue;
import io.edap.http.ValueHttpRequest;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ValueHttpRequest} 的 header 读取行为。
 *
 * <p><b>为什么要有这个测试</b>：{@code getHaders()} 曾经把收集好的 map 丢掉、直接
 * {@code return Collections.EMPTY_MAP}，于是所有走 {@code @ParamParseConfig} 把
 * {@code headers} 字段映射到 {@code HttpRequest.getHaders()} 的接口都拿不到任何请求头。
 * 微信支付回调就是这种用法——服务端永远收到「缺少验签请求头」，而且看起来像业务逻辑问题，
 * 实际是框架层丢数据。</p>
 */
class ValueHttpRequestHeaderTest {

    @Test
    void getHadersReturnsAllRequestHeaders() {
        ValueHttpRequest req = new ValueHttpRequest();
        req.putHeader("Wechatpay-Serial", new HeaderValue("PUB_KEY_ID_MOCK"));
        req.putHeader("Wechatpay-Signature", new HeaderValue("c2lnbmF0dXJl"));
        req.putHeader("Content-Type", new HeaderValue("application/json"));

        Map<String, String> headers = req.getHeaderMap();

        assertNotNull(headers, "getHaders() 不能返回 null");
        assertEquals(3, headers.size(), "应返回全部请求头，实际: " + headers);
        assertEquals("PUB_KEY_ID_MOCK", headers.get("Wechatpay-Serial"));
        assertEquals("c2lnbmF0dXJl", headers.get("Wechatpay-Signature"));
        assertEquals("application/json", headers.get("Content-Type"));
    }

    /** 没有 header 时返回空 map（不是 null），调用方不必判空。 */
    @Test
    void getHadersOnEmptyRequestReturnsEmptyMap() {
        Map<String, String> headers = new ValueHttpRequest().getHeaderMap();

        assertNotNull(headers);
        assertTrue(headers.isEmpty(), "无 header 时应返回空 map，实际: " + headers);
    }

    /**
     * header 名保留**原始大小写**（{@code Wechatpay-Serial}）——
     * 业务侧是 {@code headers.get("Wechatpay-Serial")} 这种精确大小写查找。
     */
    @Test
    void headerNameKeepsOriginalCase() {
        ValueHttpRequest req = new ValueHttpRequest();
        req.putHeader("Wechatpay-Nonce", new HeaderValue("abc123"));

        assertTrue(req.getHeaderMap().containsKey("Wechatpay-Nonce"),
                "header 名应保留原始大小写，实际: " + req.getHeaderMap().keySet());
    }
}
