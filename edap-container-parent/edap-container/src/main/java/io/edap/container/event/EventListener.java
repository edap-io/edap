package io.edap.container.event;

/**
 * 事件订阅者 functional interface。subscribe(type, listener) 时按 type 注册，
 * publish 时按事件实际 Class 找到对应 listener 列表，逐个 onEvent 调用。
 *
 * @param <T> 订阅的事件类型
 */
@FunctionalInterface
public interface EventListener<T extends ApplicationEvent> {

    void onEvent(T event);
}