package io.edap.container.app;

/**
 * 容器回调接口：注入当前 AppContext 的 RouterHub（路由注册中心）。
 *
 * BeanContainer.injectAware 在 Phase 2 COMMITTING 阶段回调。bean 通过 routers 拿到 HTTP/WS/eRPC/gRPC
 * 四份 Handler List，可在运行时动态 bind/unbind（如热加载子路由、灰度绑定）。
 *
 * 注意：RouterHub 已挂在 AppContext 上，本接口只是把 AppContext 暴露给需要"既要知道 AppContext 又要操作
 * 路由"的 bean 提供便利回调。绝大多数 bean 应通过 Environment.getBean(RouterHub.class) 获取。
 *
 * 模块归属：io.edap.container.app——和 RouterHub 同包，避免在 io.edap.container 顶层引入 app 子包依赖。
 */
public interface RouterHubAware {

    void setRouterHub(RouterHub routerHub);
}