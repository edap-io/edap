package io.edap.container;

/**
 * 容器回调接口：注入当前 Environment（应用配置视图）。
 *
 * BeanContainer.injectAware 在 Phase 2 COMMITTING 阶段回调。bean 通过 env 读取 build.json env 段 /
 * container Props 合并结果，通过 env.getBean(...) 间接查其他 bean。
 */
public interface EnvironmentAware {

    void setEnvironment(Environment environment);
}