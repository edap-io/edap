package io.edap.container.test.configuration;

import io.edap.container.BeanContainer;
import io.edap.container.BeanDef;
import io.edap.container.BeanWrap;
import io.edap.microservice.Scope;
import io.edap.microservice.annotation.Bean;
import io.edap.microservice.annotation.Configuration;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 复现：{@code @Configuration + @Bean} 工厂方法返回类型实现某接口，
 * 调用 {@link BeanContainer#beanWrapsByType} / {@code getBeansOfType(Interface.class)}
 * 是否能命中。
 *
 * <p>对照真实场景：{@code io.edap.s3.starter.conf.Config#s3UrlMapping} 返回
 * {@code S3UrlMapping implements UrlMapping}，调用方查 {@code getBeansOfType(UrlMapping.class)}。</p>
 */
public class ConfigBeanByInterfaceTest {

    public interface GreetingService {
        String greet();
    }

    public interface Auditable {
        default String tag() { return "audit"; }
    }

    public static class GreeterImpl implements GreetingService, Auditable {
        @Override public String greet() { return "hello"; }
    }

    @Configuration(name = "ifaceConfig")
    public static class IfaceConfig {
        @Bean(name = "greeter")
        public GreeterImpl greeter() {
            return new GreeterImpl();
        }
    }

    @Test
    void factoryMethodResultVisibleByInterface() throws Exception {
        Method m = IfaceConfig.class.getDeclaredMethod("greeter");
        List<BeanDef> defs = new ArrayList<>();
        defs.add(new BeanDef("ifaceConfig", IfaceConfig.class, Scope.SINGLETON,
                null, null, null, null, 0));
        defs.add(new BeanDef("greeter", GreeterImpl.class, Scope.SINGLETON,
                List.of("ifaceConfig"), null, null, null, 0, m, "ifaceConfig"));

        BeanContainer beans = new BeanContainer(null, null, null, null);
        for (BeanDef d : defs) beans.register(d);
        List<BeanDef> sorted = beans.topologicalSort();
        beans.transitionToCommitting();
        for (BeanDef d : sorted) {
            Object inst = beans.instantiate(d);
            beans.injectDependencies(d, inst);
            beans.invokeInit(d, inst);
            beans.registerInstance(d, inst);
        }

        // 直查 bean 名
        GreeterImpl g = (GreeterImpl) beans.getBean("greeter");
        assertNotNull(g);

        // by concrete type
        List<BeanWrap> concrete = beans.beanWrapsByType(GreeterImpl.class);
        assertEquals(1, concrete.size(), "byType[GreeterImpl] 应有 1 个");

        // by interface — 这才是用户报错的场景
        List<BeanWrap> byIface = beans.beanWrapsByType(GreetingService.class);
        assertEquals(1, byIface.size(),
                "byType[GreetingService] 应有 1 个 — @Bean 工厂方法实例应通过 collectTypeTokens 注册到接口 token");

        List<BeanWrap> byAudit = beans.beanWrapsByType(Auditable.class);
        assertEquals(1, byAudit.size(), "byType[Auditable] 也应命中（多接口）");
    }
}
