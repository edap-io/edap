package io.edap.container.event;

import io.edap.container.AppContext;

/**
 * AppContext 启动完成事件。AppContext.start() Phase 3 完成后由 {@code EventPublisher} 发布。
 *
 * <p><b>触发时机</b>：{@code state.transitionTo(AppState.RUNNING)} 之后；
 * 具体位置见 §4.2.3 {@code AppContext.start()}。</p>
 *
 * <p><b>典型用途</b>：</p>
 * <ul>
 *   <li>业务 bean 收到通知后做 post-start 钩子（不通过 {@code @PostConstruct}——那个跑在 Phase 2）</li>
 *   <li>监控 / 告警上报"app 已就绪"</li>
 *   <li>测试代码 await 启动完成（替代 sleep / 轮询 state()）</li>
 * </ul>
 *
 * <p><b>一次性事件</b>：每个 AppContext 生命周期内最多 publish 一次（start() 成功路径上）。
 * start() 抛错转 FAILED 时不发——避免误导监听者以为启动成功。</p>
 */
public final class ContextRefreshedEvent extends ApplicationEvent {

    private final AppContext appContext;

    public ContextRefreshedEvent(AppContext appContext) {
        super(appContext);
        this.appContext = appContext;
    }

    /** 触发事件的 AppContext（已 RUNNING；state() / beans() / routers() 均可安全访问）。 */
    public AppContext appContext() {
        return appContext;
    }
}