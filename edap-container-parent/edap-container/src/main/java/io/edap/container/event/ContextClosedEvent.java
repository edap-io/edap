package io.edap.container.event;

import io.edap.container.AppContext;

/**
 * AppContext 关闭完成事件。{@code AppContext.stop()} 销毁路径走完后由 {@code EventPublisher} 发布。
 *
 * <p><b>触发时机</b>：{@code state.transitionTo(AppState.STOPPED)} <b>之前</b>（此时 bean /
 * ClassLoader 还未完全释放，listener 仍可访问 {@code appContext.beans()} 等做最后清理）；
 * 具体位置见 §4.2.3 {@code AppContext.stop()}。</p>
 *
 * <p><b>典型用途</b>：</p>
 * <ul>
 *   <li>监控 / 告警上报"app 已下线"</li>
 *   <li>测试代码 await 关闭完成（替代 sleep / 轮询 state()）</li>
 *   <li>业务 bean 收到通知做收尾（不通过 {@code @PreDestroy}——那个跑在
 *       {@code destroyAllSingletons} 内，且调用顺序早于此事件）</li>
 * </ul>
 *
 * <p><b>一次性事件</b>：每个 AppContext 生命周期内最多 publish 一次（成功 stop 路径上）。
 * stop() 失败转 FAILED 时<b>不发</b>此事件——避免误导监听者以为关闭成功。</p>
 *
 * <p><b>为什么不在 STOPPED 之后发布</b>：{@code AppContext.stop()} 内
 * {@code beans.destroyAllSingletons()} 已先清空所有 singleton bean——之后再 publish
 * ContextClosedEvent，listener 内 {@code appContext.beans()} 拿不到东西，调试困难。
 * 当前实现是在 destroyAllSingletons + appCL.close <b>之后</b>、{@code state.transitionTo(STOPPED)}
 * <b>之前</b> publish，listener 仍能访问 beans()（已空，但容器自身引用链还在）。</p>
 */
public final class ContextClosedEvent extends ApplicationEvent {

    private final AppContext appContext;

    public ContextClosedEvent(AppContext appContext) {
        super(appContext);
        this.appContext = appContext;
    }

    /**
     * 触发事件的 AppContext（state 即将转 STOPPED，beans() 已清空，appCL 已 close——
     * listener 不应再依赖具体 bean，只能查询 appContext 自身元信息如 appId / version / dmd）。
     */
    public AppContext appContext() {
        return appContext;
    }
}