package io.edap.container;

public enum BeanContainerState {
    COLLECTING,       // Phase 1：register BeanDef
    INSTANTIATING,    // Phase 2：instantiate / inject / init
    READY,            // Phase 3 完成：singletons 可被业务读
    DESTROYING,       // stop() 进入：Lifecycle.stop / @PreDestroy
    DESTROYED;        // terminal

    /**
     * 严格状态断言：当前状态必须等于 expected，否则抛 IllegalStateException。
     * 用于 register / instantiate 等阶段方法的入口校验。
     */
    public void checkTransitionGuard(BeanContainerState expected) {
        if (this != expected) {
            throw new IllegalStateException(
                    "BeanContainer state " + this + " ≠ expected " + expected);
        }
    }

    /**
     * 纯逻辑判定：当前状态是否允许迁移到 to。仅校验，不更新任何字段。
     * 真正的"校验 + 写回"由 BeanContainer 私有 transitionTo(BeanContainerState) 封装，
     * 避免 enum 不知道 own 谁、caller 又忘了赋值的死锁类 bug。
     */
    public boolean canTransitionTo(BeanContainerState to) {
        switch (this) {
            case COLLECTING:    return to == INSTANTIATING;
            case INSTANTIATING: return to == READY;
            case READY:         return to == DESTROYING;
            case DESTROYING:    return to == DESTROYED;
            case DESTROYED:     return false;
            default:            return false;
        }
    }
}