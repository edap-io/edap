package io.edap.container;

/**
 * AppContext 生命周期状态机。
 *
 * <pre>
 *              ┌──→ FAILED ──→ STOPPING ──→ STOPPED
 *              │
 *  NEW → GATHERING → COMMITTING → READY → RUNNING
 *                                  │           │
 *                                  └──── STOPPING ┘
 * </pre>
 *
 * 状态迁移由 lifecycleLock（AppContext 持有）串行化；并发模型详见 §3.9。
 *
 * 注意：与 {@code BeanContainerState}（{@code COLLECTING / INSTANTIATING / READY / DESTROYING / DESTROYED}）
 * 不是同一层——本枚举是 AppContext 级（应用进程内子状态），BeanContainer 状态是装配细粒度。
 * 映射规则：
 *   - GATHERING      ↔ BeanContainerState.COLLECTING
 *   - COMMITTING     ↔ BeanContainerState.INSTANTIATING
 *   - READY/RUNNING  ↔ BeanContainerState.READY
 *   - STOPPING       ↔ BeanContainerState.DESTROYING
 *   - STOPPED        ↔ BeanContainerState.DESTROYED
 */
public enum AppState {
    NEW,         // 刚 new 出来，未 start()
    GATHERING,   // Phase 1：扫 EAR + 注册 BeanDef
    COMMITTING,  // Phase 2：实例化 / 注入 / @PostConstruct
    READY,       // Phase 3 完成（Bean 装配好），routes 未 bind
    RUNNING,     // routes 已 bind，业务 dispatch 可达
    STOPPING,    // stop() 进入：unbindAll + destroy
    STOPPED,     // terminal
    FAILED;      // 启动或运行期出错（terminal 之一）

    public boolean canTransitionTo(AppState to) {
        switch (this) {
            case NEW:        return to == GATHERING || to == FAILED;
            case GATHERING:  return to == COMMITTING || to == FAILED;
            case COMMITTING: return to == READY      || to == FAILED;
            case READY:      return to == RUNNING    || to == STOPPING || to == FAILED;
            case RUNNING:    return to == STOPPING   || to == FAILED;
            case STOPPING:   return to == STOPPED    || to == FAILED;
            case STOPPED:    return false;
            case FAILED:     return to == STOPPING;        // 失败后仍可走 stop() 收尾
            default:         return false;
        }
    }

    public boolean isTerminal()  { return this == STOPPED || this == FAILED; }
    public boolean isServing()   { return this == RUNNING; }
    public boolean isStopping()  { return this == STOPPING || this == STOPPED; }
}