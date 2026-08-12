package io.edap.container.event;

/**
 * Bean 注入失败事件。Phase 2 injectDependencies / invokeInit 阶段抛错时构造此事件，
 * 由 EventPublisher.publish 派发给订阅者（如告警 / 监控）。
 *
 * 设计意图：注入失败是部署期问题，AppContext.start 主线程 catch → destroyPartial() 回滚，
 * 同时 publish 此事件给监听者做告警/记录。事件本身不触发任何路由层行为。
 */
public final class BeanInjectFailedEvent extends ApplicationEvent {

    private final String    beanName;
    private final Throwable cause;

    public BeanInjectFailedEvent(String beanName, Throwable cause) {
        super(beanName);
        this.beanName = beanName;
        this.cause    = cause;
    }

    public String    beanName() { return beanName; }
    public Throwable cause()    { return cause; }
}