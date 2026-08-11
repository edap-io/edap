package io.edap.container;

/**
 * 容器回调接口：注入当前 AppContext。
 *
 * BeanContainer.injectAware 在 Phase 2 COMMITTING 阶段检查 bean 是否实现本接口，是则回调
 * setApplicationContext(AppContext)。典型用途：bean 需要直接与 AppContext 交互（但不推荐——尽量用
 * Environment.getBean 等间接路径以避免 bean 与 AppContext 形成强耦合）。
 */
public interface ApplicationContextAware {

    void setApplicationContext(AppContext appContext);
}