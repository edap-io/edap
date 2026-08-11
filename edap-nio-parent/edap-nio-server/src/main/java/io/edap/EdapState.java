package io.edap;

public enum EdapState {
    NEW,              // 刚 new 出来，未 run()
    STARTING,         // run() 进入，nio.start() 阶段
    RUNNING,          // nio + 所有 Server 已 start；可继续 addServerGroup
    STARTING_FAILED,  // run() 期间出错
    STOPPING,         // stop() 进入，逆序清理
    STOPPED;          // terminal

    /**
     * 校验迁移合法性；非法迁移抛 IllegalStateException。
     * 由 lifecycleLock 串行化保证多线程下不会被并发踩到非法迁移。
     */
    public void checkTransitionTo(EdapState to) {
        if (!canTransitionTo(to)) {
            throw new IllegalStateException(
                    "Illegal EdapState transition: " + this + " -> " + to);
        }
    }

    public boolean canTransitionTo(EdapState to) {
        switch (this) {
            case NEW:             return to == STARTING;
            case STARTING:        return to == RUNNING || to == STARTING_FAILED;
            case RUNNING:         return to == STOPPING;
            case STARTING_FAILED: return to == STOPPING;
            case STOPPING:        return to == STOPPED;
            case STOPPED:         return false;
            default:              return false;
        }
    }

    // —— 查询辅助 ——
    public boolean isTerminal()  { return this == STOPPED; }
    public boolean isRunning()   { return this == RUNNING; }
    public boolean isStarted()   { return this != NEW; }
    public boolean isStopping()  { return this == STOPPING || this == STOPPED; }
}

