package io.edap.container;

/**
 * 容器回调接口：注入当前 bean 名（BeanDef.name()）。
 *
 * BeanContainer.injectAware 在 Phase 2 COMMITTING 阶段回调。典型用途：bean 想知道自己在容器中的标识
 * （如用于日志、metrics 标签、动态查找自身引用）。
 */
public interface BeanNameAware {

    void setBeanName(String name);
}