package io.edap.container.event;

import io.edap.container.AppContext;

/**
 * 锚点 2 通知事件：AppContext 完成 bean 装配 + {@link io.edap.container.Lifecycle#start()} +
 * 路由生成 + pathTable 构建，<b>即将</b>把 pathTable 交给
 * {@link io.edap.container.Container#deployAppRoutes} 前 publish。
 *
 * <p>与 {@link io.edap.container.ContextStartedAware} 并行：业务 bean 优先用接口
 * （类型安全 + 自动发现），框架代码 / 横切关注点用本事件（更解耦、不污染业务 bean）。</p>
 *
 * <p>触发位置见 {@link AppContext#generateAndBindRoutes()} —— 在 {@code buildPathTable()}
 * 之后、{@code container.deployAppRoutes(...)} 之前。</p>
 *
 * <p><b>listener 行为</b>：沿用 {@link EventPublisher} 默认语义 —— 抛错记 WARN 后继续，
 * 不影响其他 listener 与本次 publish。如果业务对一致性要求高（典型：listener 在改
 * pathTable 这种关键状态），请改用
 * {@link io.edap.container.ContextStartedAware} 接口（fail-fast 抛错即 start 失败）。</p>
 *
 * <p><b>一次性事件</b>：每个 AppContext 生命周期内最多 publish 一次（{@code start()} 成功路径上）。
 * {@code start()} 抛错转 {@code FAILED} 时不发 —— 避免误导监听者以为启动成功。</p>
 */
public final class ContextStartedEvent extends ApplicationEvent {

    private final AppContext appContext;

    public ContextStartedEvent(AppContext appContext) {
        super(appContext);
        this.appContext = appContext;
    }

    /**
     * 触发事件的 AppContext。
     *
     * <p>事件发布时机对应的 state：{@code COMMITTING}（{@code transitionTo(READY)} 之前）。
     * 所有 bean 已 instantiate + inject + postProcess + {@code Lifecycle.start}；路由已生成；
     * {@link AppContext#getPathInfoMap} 已 build；{@code Container.deployAppRoutes} 尚未调用。</p>
     *
     * <p>listener 想读 pathTable 请走 {@link AppContext#getPathInfoMap()}，事件本身
     * 不带 pathInfoMap 字段 —— 避免事件成为"双向通信"形态（事件应是通知而非命令）。</p>
     */
    public AppContext appContext() {
        return appContext;
    }
}