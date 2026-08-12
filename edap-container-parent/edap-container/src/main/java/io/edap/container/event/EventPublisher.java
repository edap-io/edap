package io.edap.container.event;

import io.edap.log.Logger;
import io.edap.log.LoggerManager;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 容器事件发布器。订阅按"事件 Class"精确路由：subscribe(Class, listener) 注册到
 * {@code subscribers}，publish(event) 时按 event.getClass() 取对应列表逐个 onEvent。
 *
 * 并发语义：
 *   - 启动期（Phase 1/2/3）单线程 subscribe + publish，无需锁
 *   - 运行时多线程 publish（路由调用时发 RouteInvokeErrorEvent 等），用 CopyOnWriteArrayList
 *     保证 subscribe（add）和 publish（iterator）的弱一致性：subscribe 新增的 listener 可能在
 *     下次 publish 才会被看到；现有 listener 不会在迭代中被并发移除
 *   - 监听器抛错不影响其他监听器与本次 publish，catch Throwable 后继续
 *
 * 典型事件：
 *   - BeanInjectFailedEvent：Phase 2 注入失败
 *   - RouteInvokeErrorEvent：运行时 Handler handle() 抛错
 *
 * 失败事件再发：监听器抛错时 publish 一个 BeanInjectFailedEvent(listener, t)，给告警链路兜底。
 */
public class EventPublisher {

    private static final Logger log = LoggerManager.getLogger(EventPublisher.class);

    /** key = 事件 Class，value = 该类型的所有监听器列表（线程安全）。 */
    private final ConcurrentHashMap<Class<?>, CopyOnWriteArrayList<EventListener<?>>> subscribers =
            new ConcurrentHashMap<>();

    /**
     * 派发事件到订阅了该事件 Class 的所有监听器。监听器抛错记 WARN 后继续，
     * 不影响其他监听器与本次 publish 的整体流程。
     */
    public void publish(ApplicationEvent e) {
        CopyOnWriteArrayList<EventListener<?>> list = subscribers.get(e.getClass());
        if (list == null || list.isEmpty()) return;
        for (EventListener<?> listener : list) {
            try {
                ((EventListener<ApplicationEvent>) listener).onEvent(e);
            } catch (Throwable t) {
                log.warn("listener error: event={}",
                        l -> l.arg(e.getClass().getName()).threw(t));
                // 失败事件再发（按需——避免再次失败时无限递归，仅在确实非 BeanInjectFailedEvent 时）
                if (!(e instanceof BeanInjectFailedEvent)) {
                    publish(new BeanInjectFailedEvent(listener.getClass().getName(), t));
                }
            }
        }
    }

    /**
     * 订阅事件类型 T。listener 会在 publish(T 的实际类型) 时被调用。
     * 同一 listener 可重复订阅，subscribe 是 add 语义（去重由 caller 负责）。
     */
    public <T extends ApplicationEvent> void subscribe(Class<T> type, EventListener<T> listener) {
        subscribers.computeIfAbsent(type, k -> new CopyOnWriteArrayList<>()).add(listener);
    }

    /** 取消订阅——按 instance 移除。监听器列表用 CopyOnWriteArrayList，remove 安全。 */
    public <T extends ApplicationEvent> void unsubscribe(Class<T> type, EventListener<T> listener) {
        CopyOnWriteArrayList<EventListener<?>> list = subscribers.get(type);
        if (list != null) list.remove(listener);
    }

    /** 当前已注册的事件类型数（用于自检）。 */
    public int subscribedTypes() {
        return subscribers.size();
    }

    /** 清空所有订阅（AppContext.stop 期间调用，释放 listener 引用链）。 */
    public void clear() {
        subscribers.clear();
    }
}