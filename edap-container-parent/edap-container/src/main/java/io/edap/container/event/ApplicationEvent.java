package io.edap.container.event;

/**
 * 容器事件的基类。EventPublisher.publish(ApplicationEvent) 派发给订阅该事件类型的 listener。
 *
 * 设计要点：
 *   - 事件按"实际 Class"路由（不是按 instanceOf），支持精确类型订阅
 *   - 事件本身是不可变数据载体——构造时定下，listener 不应修改它
 */
public abstract class ApplicationEvent {

    private final Object source;
    private final long   timestamp;

    protected ApplicationEvent(Object source) {
        this.source    = source;
        this.timestamp = System.currentTimeMillis();
    }

    public Object source()    { return source; }
    public long   timestamp() { return timestamp; }
}