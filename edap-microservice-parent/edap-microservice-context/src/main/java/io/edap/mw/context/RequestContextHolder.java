package io.edap.mw.context;

/**
 * 线程级 {@link RequestContext} 容器 —— 由 ASM 生成的 {@code HttpHandler} 在 try/finally
 * 里 {@link #set(RequestContext)} / {@link #clear()} 调用,业务实现类通过
 * {@link #current()} 取当前请求的用户信息。
 *
 * <p><b>为什么 ThreadLocal 而非 InheritableThreadLocal</b>:容器 NIO 事件循环 + 线程池场景
 * 下,父线程 ctx 透传到子线程会污染独立请求语义(子线程可能处理另一个请求的剩余工作)。
 * 异步场景应显式传递 ctx,不要依赖 ThreadLocal 自动继承。</p>
 *
 * <p><b>为什么用 {@link ThreadLocal#remove()} 而不是 {@code set(null)}</b>:Java 17+ 推荐
 * remove(),在线程复用场景(线程池不显式 remove 时)避免 ThreadLocal 内部 Entry 持有
 * null value 残留,降低内存泄漏风险。</p>
 *
 * <p><b>关键调用时机</b>:Handler 入口 {@link #set} → service method 调用 → 异常 / 正常
 * 完成时都执行 {@link #clear}。如果只 set 不 clear,下一个复用线程的请求会拿到上一个
 * 请求的 userId —— 数据泄漏 bug。</p>
 */
public final class RequestContextHolder {

    private static final ThreadLocal<RequestContext> CTX = new ThreadLocal<>();

    private RequestContextHolder() {}

    public static void set(RequestContext ctx) {
        CTX.set(ctx);
    }

    public static RequestContext current() {
        return CTX.get();
    }

    public static void clear() {
        CTX.remove();
    }
}