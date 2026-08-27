package io.edap.container.context;

import io.edap.http.HttpRequest;

import java.io.IOException;

/**
 * 当前请求用户上下文解析器。{@code @RequireAuth(resolver = "xxx")} 标注的方法在
 * 生成的 HttpHandler 入口被调用:从 {@link HttpRequest} 取出当前用户,产出
 * {@link RequestContext} set 进 {@link RequestContextHolder}。
 *
 * <p><b>resolver 是 bean 名</b>:{@code @RequireAuth} 注解空字符串 → 默认 bean name
 * {@code "jwtUserResolver"};业务可自定义实现 + 同名 bean 注册覆盖默认。</p>
 *
 * <p><b>为什么抛 {@link IOException}</b>:统一覆盖 "缺 Authorization 头 / token 解码失败 /
 * 签名错 / 业务校验失败" 三类情况,Handler 不需要写多 catch 分支。</p>
 *
 * <p><b>跨 CL 友好的关键</b>:实现类应注入接口类型(不是具体实现类),避免 ClassLoader 身份
 * 不匹配导致 {@code isAssignableFrom} 失败。</p>
 */
public interface UserResolver {

    /**
     * 从 {@link HttpRequest} 解析当前用户上下文。
     *
     * @param req 当前 HTTP 请求
     * @return 解析出的 {@link RequestContext},Handler 会 set 到 {@link RequestContextHolder}
     * @throws IOException 缺鉴权信息 / 凭据无效 / 业务校验失败
     */
    RequestContext resolve(HttpRequest req) throws IOException;
}