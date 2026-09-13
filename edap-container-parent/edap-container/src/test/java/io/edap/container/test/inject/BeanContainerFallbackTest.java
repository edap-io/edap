package io.edap.container.test.inject;

import io.edap.container.BeanContainer;
import io.edap.container.BeanDef;
import io.edap.container.Container;
import io.edap.container.exc.NoSuchBeanException;
import io.edap.microservice.Scope;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 验证 {@link BeanContainer#getBean(String)} 在 AppContext 级 miss 时
 * 自动 fallback 到 {@link Container#containerBeans()}——保证框架默认 bean
 * （如 {@code jwtUserResolver}）对生成的 handler 可见。
 */
class BeanContainerFallbackTest {

    /** 一个用于注册的 marker 类型 —— containerCL 加载 */
    public static class FrameworkBean {
        public FrameworkBean() {}
        public String tag() { return "framework"; }
    }

    /** appCL 端的占位 bean —— 走本地 lookup 路径 */
    public static class AppBean {
        public AppBean() {}
        public String value() { return "v"; }
    }

    @Test
    void getBeanByNameFallsBackToContainerBeans() throws Exception {
        Container c = newContainerWithFrameworkBean();

        // 不构造 AppContext（构造 AppContext 太重），直接 new BeanContainer 然后反射
        // 把 container 字段填上 —— 与生产路径 appContext.container() 拿到的引用一致。
        BeanContainer appBeans = new BeanContainer(null, null, null, null);
        Field f = BeanContainer.class.getDeclaredField("container");
        f.setAccessible(true);
        f.set(appBeans, c);

        // 模拟"框架默认 bean 在 Container.beans 注册、AppContext.beans 没注册"
        // —— 这里直接构造一个 AppContext 级 beans,只注册一个 app 自己的 bean
        BeanDef appDef = new BeanDef("appBean", AppBean.class, Scope.SINGLETON,
                null, null, null, null, 0);
        appBeans.register(appDef);
        appBeans.transitionToCommitting();
        Object inst = appBeans.instantiate(appDef);
        appBeans.injectDependencies(appDef, inst);
        appBeans.invokeInit(appDef, inst);
        appBeans.registerInstance(appDef, inst);

        // app 自己的 bean 走本地
        assertEquals("v", ((AppBean) appBeans.getBean("appBean")).value());

        // 框架默认 bean 走 fallback
        assertEquals("framework", ((FrameworkBean) appBeans.getBean("frameworkBean")).tag());
    }

    @Test
    void getBeanByNameStillThrowsWhenNeitherSideHasIt() throws Exception {
        Container c = newContainerWithFrameworkBean();
        BeanContainer appBeans = new BeanContainer(null, null, null, null);
        Field f = BeanContainer.class.getDeclaredField("container");
        f.setAccessible(true);
        f.set(appBeans, c);

        assertThrows(NoSuchBeanException.class, () -> appBeans.getBean("doesNotExist"));
    }

    private static Container newContainerWithFrameworkBean() throws Exception {
        // Container(File) 构造后 containerBeans 还是 null，initContainerBeans 又依赖 edap/env
        // —— 直接反射塞一个手搓的 BeanContainer，绕开 Container 全套生命周期。
        Container c = new Container(new File("ignored"), java.util.Set.of());
        BeanContainer containerBeans = new BeanContainer(null, null, null, null);
        java.lang.reflect.Field f = Container.class.getDeclaredField("containerBeans");
        f.setAccessible(true);
        f.set(c, containerBeans);

        BeanDef frameworkDef = new BeanDef("frameworkBean", FrameworkBean.class,
                Scope.SINGLETON, null, null, null, null, 0);
        containerBeans.register(frameworkDef);
        containerBeans.transitionToCommitting();
        Object inst = containerBeans.instantiate(frameworkDef);
        containerBeans.injectDependencies(frameworkDef, inst);
        containerBeans.invokeInit(frameworkDef, inst);
        containerBeans.registerInstance(frameworkDef, inst);
        return c;
    }
}
