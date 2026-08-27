package io.edap.container.test.configuration;

import io.edap.container.BeanContainer;
import io.edap.container.BeanDef;
import io.edap.microservice.Scope;
import io.edap.microservice.annotation.Bean;
import io.edap.microservice.annotation.Configuration;
import io.edap.microservice.annotation.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code @Configuration + @Bean} 工厂方法路径的单元测试。
 *
 * <p>绕过 AppContext + EAR 扫描，直接构造 {@link BeanDef} + {@link BeanContainer}：
 * <ul>
 *   <li>BeanContainer 用全 null 构造（AppContext/Environment/EventPublisher/ShardRegistry）——
 *       测试只 @Inject 自定义类型，不触发这几个的解析路径，避免 mock 整套上下文</li>
 *   <li>手动调 register → topologicalSort → transitionToCommitting → 对每个 BeanDef
 *       依次 instantiate / injectDependencies / invokeInit / registerInstance</li>
 * </ul>
 */
public class ConfigurationTest {

    private BeanContainer beans;

    @BeforeEach
    void setUp() throws Exception {
        // 全 null 构造：测试 Bean 不依赖 AppContext/Environment/EventPublisher
        beans = new BeanContainer(null, null, null, null);
    }

    /** 注册 → 拓扑排序 → 进入 COMMITTING 阶段，返回排序后的 BeanDef 列表。 */
    private List<BeanDef> commit(List<BeanDef> defs) throws Exception {
        for (BeanDef d : defs) {
            beans.register(d);
        }
        List<BeanDef> sorted = beans.topologicalSort();
        beans.transitionToCommitting();
        for (BeanDef d : sorted) {
            Object inst = beans.instantiate(d);
            beans.injectDependencies(d, inst);
            beans.invokeInit(d, inst);
            beans.registerInstance(d, inst);
        }
        return sorted;
    }

    @Test
    void basicFactoryMethod() throws Exception {
        List<BeanDef> defs = List.of(
                new BeanDef("appConfig", AppConfig.class, Scope.SINGLETON,
                        null, null, null, null, 0)
        );
        // 手工加一个 @Bean BeanDef（buildConfigurationBeanDefs 的产出形态）
        java.lang.reflect.Method m = AppConfig.class.getDeclaredMethod("greeter");
        defs = new java.util.ArrayList<>(defs);
        defs.add(new BeanDef("greeter", Greeter.class, Scope.SINGLETON,
                List.of("appConfig"), null, null, null, 0, m, "appConfig"));

        commit(defs);

        Greeter g = (Greeter) beans.getBean("greeter");
        assertNotNull(g);
        assertEquals("hello", g.greet());
        // 配置类自己也注册为 bean
        AppConfig cfg = (AppConfig) beans.getBean("appConfig");
        assertNotNull(cfg);
    }

    @Test
    void factoryMethodWithInjectParam() throws Exception {
        java.lang.reflect.Method mDataSource = DataSourceConfig.class.getDeclaredMethod("dataSource");
        java.lang.reflect.Method mRepo = RepoConfig.class.getDeclaredMethod("userRepository", javax.sql.DataSource.class);

        List<BeanDef> defs = new java.util.ArrayList<>();
        defs.add(new BeanDef("dataSourceConfig", DataSourceConfig.class, Scope.SINGLETON,
                null, null, null, null, 0));
        defs.add(new BeanDef("dataSource", javax.sql.DataSource.class, Scope.SINGLETON,
                List.of("dataSourceConfig"), null, null, null, 0, mDataSource, "dataSourceConfig"));
        defs.add(new BeanDef("repoConfig", RepoConfig.class, Scope.SINGLETON,
                null, null, null, null, 0));
        defs.add(new BeanDef("userRepository", UserRepository.class, Scope.SINGLETON,
                List.of("repoConfig"), null, null, null, 0, mRepo, "repoConfig"));

        commit(defs);

        UserRepository repo = (UserRepository) beans.getBean("userRepository");
        assertNotNull(repo);
        assertNotNull(repo.dataSource(), "@Inject DataSource 应来自 dataSourceConfig 的 @Bean 工厂方法");
    }

    @Test
    void configurationInstantiatedBeforeBeanMethods() throws Exception {
        java.lang.reflect.Method m = OrderConfig.class.getDeclaredMethod("earlyBean");
        List<BeanDef> defs = List.of(
                new BeanDef("orderConfig", OrderConfig.class, Scope.SINGLETON,
                        null, null, null, null, 0),
                new BeanDef("earlyBean", EarlyBean.class, Scope.SINGLETON,
                        List.of("orderConfig"), null, null, null, 0, m, "orderConfig")
        );

        commit(defs);

        // 配置类实例化必须先于 @Bean 方法——若顺序错，OrderConfig.constructedAt 会 > earlyBean.constructedAt
        OrderConfig cfg = (OrderConfig) beans.getBean("orderConfig");
        EarlyBean eb = (EarlyBean) beans.getBean("earlyBean");
        assertNotNull(cfg);
        assertNotNull(eb);
        assertTrue(cfg.constructedAt <= eb.constructedAt,
                "@Configuration 必须先于其 @Bean 方法实例化");
    }

    @Test
    void singletonCachedAcrossLookups() throws Exception {
        java.lang.reflect.Method m = AppConfig.class.getDeclaredMethod("greeter");
        List<BeanDef> defs = new java.util.ArrayList<>(List.of(
                new BeanDef("appConfig", AppConfig.class, Scope.SINGLETON,
                        null, null, null, null, 0)
        ));
        defs.add(new BeanDef("greeter", Greeter.class, Scope.SINGLETON,
                List.of("appConfig"), null, null, null, 0, m, "appConfig"));
        commit(defs);

        Greeter g1 = (Greeter) beans.getBean("greeter");
        Greeter g2 = (Greeter) beans.getBean("greeter");
        assertSame(g1, g2, "SINGLETON @Bean 必须返回同一实例");
    }

    @Test
    void missingInjectThrowsNoSuchBean() throws Exception {
        java.lang.reflect.Method m = BadConfig.class.getDeclaredMethod("needsMissing", DefinitelyMissingType.class);
        List<BeanDef> defs = List.of(
                new BeanDef("badConfig", BadConfig.class, Scope.SINGLETON,
                        null, null, null, null, 0),
                new BeanDef("needsMissing", Object.class, Scope.SINGLETON,
                        List.of("badConfig"), null, null, null, 0, m, "badConfig")
        );

        assertThrows(io.edap.container.exc.NoSuchBeanException.class, () -> {
            commit(defs);
            beans.getBean("needsMissing");
        });
    }

    @Test
    void optionalInjectReturnsNullWhenMissing() throws Exception {
        java.lang.reflect.Method m = OptionalConfig.class.getDeclaredMethod("withOptional", DefinitelyMissingType.class);
        List<BeanDef> defs = List.of(
                new BeanDef("optConfig", OptionalConfig.class, Scope.SINGLETON,
                        null, null, null, null, 0),
                new BeanDef("withOptional", Object.class, Scope.SINGLETON,
                        List.of("optConfig"), null, null, null, 0, m, "optConfig")
        );
        // 没注册 DataSource → @Optional 缺失时为 null
        commit(defs);
        String result = (String) beans.getBean("withOptional");
        assertEquals("absent", result, "@Optional 参数在缺失时应返回 null，@Bean 方法据此返回 'absent'");
    }

    // —— 测试用 POJO ——

    @Configuration(name = "appConfig")
    public static class AppConfig {
        @Bean(name = "greeter")
        public Greeter greeter() {
            return new Greeter("hello");
        }
    }

    public static class Greeter {
        private final String msg;
        public Greeter(String msg) { this.msg = msg; }
        public String greet() { return msg; }
    }

    @Configuration(name = "dataSourceConfig")
    public static class DataSourceConfig {
        @Bean(name = "dataSource")
        public javax.sql.DataSource dataSource() {
            return new StubDataSource();
        }
    }

    @Configuration(name = "repoConfig")
    public static class RepoConfig {
        @Bean(name = "userRepository")
        public UserRepository userRepository(javax.sql.DataSource ds) {
            return new UserRepository(ds);
        }
    }

    public static class StubDataSource implements javax.sql.DataSource {
        // 空 stub：方法都不实现，测试不调用
        @Override public java.sql.Connection getConnection() { return null; }
        @Override public java.sql.Connection getConnection(String u, String p) { return null; }
        @Override public java.io.PrintWriter getLogWriter() { return null; }
        @Override public void setLogWriter(java.io.PrintWriter out) {}
        @Override public void setLoginTimeout(int seconds) {}
        @Override public int getLoginTimeout() { return 0; }
        @Override public java.util.logging.Logger getParentLogger() { return null; }
        @Override public <T> T unwrap(Class<T> iface) { return null; }
        @Override public boolean isWrapperFor(Class<?> iface) { return false; }
    }

    public static class UserRepository {
        private final javax.sql.DataSource ds;
        public UserRepository(javax.sql.DataSource ds) { this.ds = ds; }
        public javax.sql.DataSource dataSource() { return ds; }
    }

    @Configuration(name = "orderConfig")
    public static class OrderConfig {
        final long constructedAt = System.nanoTime();
        @Bean(name = "earlyBean")
        public EarlyBean earlyBean() { return new EarlyBean(); }
    }

    public static class EarlyBean {
        final long constructedAt = System.nanoTime();
    }

    @Configuration(name = "badConfig")
    public static class BadConfig {
        @Bean(name = "needsMissing")
        public Object needsMissing(DefinitelyMissingType x) {
            return new Object();
        }
    }

    public interface DefinitelyMissingType {} // 故意不注册

    @Configuration(name = "optConfig")
    public static class OptionalConfig {
        @Bean(name = "withOptional")
        public String withOptional(@Optional DefinitelyMissingType x) {
            return x == null ? "absent" : "present";  // 包装成非 null，避免 registerInstance NPE
        }
    }
}