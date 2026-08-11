package io.edap.container;

public enum BeanContainerState {
    COLLECTING,       // Phase 1：register BeanDef
    INSTANTIATING,    // Phase 2：instantiate / inject / init
    READY,            // Phase 3 完成：singletons 可被业务读
    DESTROYING,       // stop() 进入：Lifecycle.stop / @PreDestroy
    DESTROYED;        // terminal

    public void checkTransitionGuard(BeanContainerState expected) {
        if (this != expected) {
            throw new IllegalStateException(
                    "BeanContainer state " + this + " ≠ expected " + expected);
        }
    }
    public void transitionTo(BeanContainerState to) {
        if (!canTransitionTo(to)) {
            throw new IllegalStateException(
                    "Illegal BeanContainerState transition: " + this + " -> " + to);
        }
        // setter 由 lifecycleLock 串行化（AppContext 持有）
    }
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