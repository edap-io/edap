package io.edap.container;

public enum ContainerState {
    NEW,             // 刚 new 出来
    ATTACHED,        // 已 attach(edap)，可调 start()
    STARTING,        // 扫描 appsDir
    RUNNING,         // 正常服务中
    DEPLOYING,       // deploy() 临界区（deployLock 内）
    UNDEPLOYING,     // undeploy() 临界区（deployLock 内）
    SWITCHING,       // switchVersion() 临界区（deployLock 内）
    START_FAILED,    // start() 致命错
    STOPPING,        // 关闭中
    STOPPED;         // terminal

    public void checkTransitionTo(ContainerState to) {
        if (!canTransitionTo(to)) {
            throw new IllegalStateException(
                    "Illegal ContainerState transition: " + this + " -> " + to);
        }
    }

    public boolean canTransitionTo(ContainerState to) {
        switch (this) {
            case NEW:         return to == ATTACHED;
            case ATTACHED:    return to == STARTING;
            case STARTING:    return to == RUNNING || to == START_FAILED;
            case RUNNING:     return to == DEPLOYING || to == UNDEPLOYING
                    || to == SWITCHING || to == STOPPING;
            case DEPLOYING:   return to == RUNNING;
            case UNDEPLOYING: return to == RUNNING;
            case SWITCHING:   return to == RUNNING;
            case START_FAILED: return to == STOPPING;
            case STOPPING:    return to == STOPPED;
            case STOPPED:     return false;
            default:          return false;
        }
    }

    // —— 查询辅助 ——
    public boolean isTerminal()     { return this == STOPPED; }
    public boolean isRunning()      { return this == RUNNING; }
    public boolean isServing()      { return this == RUNNING
            || this == DEPLOYING || this == UNDEPLOYING
            || this == SWITCHING; }
    public boolean isStarting()     { return this == ATTACHED || this == STARTING; }
    public boolean isStopping()     { return this == STOPPING || this == STOPPED; }
}
