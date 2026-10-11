package io.edap.container;

/**
 * 锚点 2 通知接口：AppContext 完成 bean 装配 + {@link Lifecycle#start()} + 路由生成 +
 * pathTable 构建，<b>即将</b>把 pathTable 交给 {@link Container#deployAppRoutes} 前回调。
 *
 * <p>触发位置见 {@link AppContext#generateAndBindRoutes()} —— 在
 * {@code buildPathTable()} 之后、{@code container.deployAppRoutes(...)} 之前。
 * 调用顺序按 BeanDef 拓扑序（被依赖的 bean 先 ready）。</p>
 *
 * <p><b>适合做</b>：
 * <ul>
 *   <li>修改 {@link AppContext#getPathInfoMap()} 内的 {@code PathInfo}（注入
 *       WSAuthenticator、限流 handler、metrics 拦截）</li>
 *   <li>对外部 LB / 服务发现发 ready 信号</li>
 *   <li>启动后端长连接（IM / push / webhook 客户端）</li>
 *   <li>上报 "app ready to serve" metrics</li>
 * </ul>
 *
 * <p><b>不应做</b>：
 * <ul>
 *   <li>调 {@link Container#deployAppRoutes} —— 通知完成后 Container 自动调</li>
 *   <li>修改 {@code RouterHub} —— 已写入，事件后再改会被覆盖</li>
 *   <li>改 {@code BeanContainer} 里的 bean 字段 —— bean 已 {@code Lifecycle.start} 过，
 *       改可能 race</li>
 * </ul>
 *
 * <p><b>异常契约（fail-fast）</b>：抛任何异常 →
 * {@link AppContext#start()} 转 {@code FAILED} 状态，deploy 回滚。同 ctx 内尚未回调的
 * bean 不会被通知（保证失败时状态一致）。</p>
 *
 * <p>框架代码 / 横切关注点（不挂在 bean 树上的 listener）改用
 * {@link io.edap.container.event.ContextStartedEvent} —— 沿用
 * {@link io.edap.container.event.EventPublisher} 的 "log warn + 继续" 语义，与
 * {@link io.edap.container.event.ContextRefreshedEvent} 一致。</p>
 */
public interface ContextStartedAware {

    /**
     * AppContext 进入 {@code COMMITTING}（即将转 READY -> RUNNING）前一刻触发。
     *
     * @param ctx 触发事件的 AppContext。{@code state()} 返回 {@code COMMITTING}；
     *            所有 bean 已 instantiate + inject + postProcess + Lifecycle.start；
     *            路由已生成、{@link AppContext#getPathInfoMap} 已 build；
     *            {@link Container#deployAppRoutes} 尚未调用。
     */
    void onContextStarted(AppContext ctx);
}