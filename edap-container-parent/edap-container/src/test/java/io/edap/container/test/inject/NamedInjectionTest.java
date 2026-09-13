package io.edap.container.test.inject;

import io.edap.container.BeanContainer;
import io.edap.container.BeanDef;
import io.edap.container.BeanNameAware;
import io.edap.container.InjectionPoint;
import io.edap.container.exc.NoSuchBeanException;
import io.edap.microservice.Scope;
import io.edap.microservice.annotation.Bean;
import io.edap.microservice.annotation.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.inject.Inject;
import javax.inject.Named;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code @Named} 按名注入的单元测试 —— 覆盖三种注入路径(构造器 / 字段 / setter) +
 * 同类型多 bean 消歧 + 缺名异常 + @Optional 协同 + 类型兜底零回归。
 *
 * <p>绕过 AppContext + EAR 扫描,直接构造 {@link BeanDef} + {@link BeanContainer}——
 * 测试只 {@code @Inject} 自定义类型,不触发 AppContext/Environment/EventPublisher 的
 * 解析路径,避免 mock 整套上下文。</p>
 *
 * <p>对每条 BeanDef 字段/方法 {@code injections} 都手工构造:
 * <ul>
 *   <li>字段注入 → {@link InjectionPoint#field},beanName 来自字段上的 {@code @Named}</li>
 *   <li>方法注入 → {@link InjectionPoint#method},beanName 留 null,
 *       由 {@link BeanContainer#resolveMethodArgs} 按参数级别解析</li>
 * </ul>
 * (复刻 {@code AppContext.scanInjectionPoints} 的产出形态,确保不依赖 AppContext 全流程)
 */
public class NamedInjectionTest {

    private BeanContainer beans;

    @BeforeEach
    void setUp() {
        // 全 null 构造:测试 Bean 不依赖 AppContext/Environment/EventPublisher
        beans = new BeanContainer(null, null, null, null);
    }

    /** 把 BeanDef 列表走完 register → topologicalSort → COMMITTING 阶段。 */
    private void commit(List<BeanDef> defs) {
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
    }

    /** 字段扫描 helper(把测试 Bean 上的 @Inject 字段手工转成 InjectionPoint)。
     *  字段上的 @Named("xxx") 优先;无 @Named → beanName 留 null,运行时按类型兜底。 */
    private static List<InjectionPoint> fieldInjections(Class<?> cls) {
        List<InjectionPoint> out = new ArrayList<>();
        for (Field f : cls.getDeclaredFields()) {
            if (f.getAnnotation(Inject.class) == null) continue;
            String name = null;
            Named n = f.getAnnotation(Named.class);
            if (n != null && !n.value().isEmpty()) name = n.value();
            out.add(InjectionPoint.field(f, name, f.getType()));
        }
        return out;
    }

    /** 通用 InjectionPoint 列表的 bean 名(供 injectionNames 拓扑用)。 */
    private static List<String> injectionNames(List<InjectionPoint> ips) {
        List<String> names = new ArrayList<>();
        for (InjectionPoint ip : ips) {
            if (ip.beanName() != null) names.add(ip.beanName());
        }
        return names.isEmpty() ? null : names;
    }

    // ───────────────────────────────────────────────────────────────
    // 1. 构造器按名
    // ───────────────────────────────────────────────────────────────
    @Test
    void ctorByName() throws Exception {
        // a / b 两个 Service bean,Consumer 通过构造器 @Named("a") 取 a
        commit(List.of(
                new BeanDef("a", Service.class, Scope.SINGLETON, null, null, null, null, 0),
                new BeanDef("b", Service.class, Scope.SINGLETON, null, null, null, null, 0),
                new BeanDef("consumer", Consumer.class, Scope.SINGLETON,
                        List.of("a"), null, null, null, 0)
        ));
        Consumer c = (Consumer) beans.getBean("consumer");
        assertNotNull(c);
        assertEquals("a", c.service().tag(),
                "构造器 @Named(\"a\") 应注入 name=a 的 bean,不是 a/b 任一");
    }

    // ───────────────────────────────────────────────────────────────
    // 2. 字段按名
    // ───────────────────────────────────────────────────────────────
    @Test
    void fieldByName() throws Exception {
        List<InjectionPoint> fieldIps = fieldInjections(FieldConsumer.class);
        commit(new ArrayList<>(List.of(
                new BeanDef("a", Service.class, Scope.SINGLETON, null, null, null, null, 0),
                new BeanDef("b", Service.class, Scope.SINGLETON, null, null, null, null, 0),
                new BeanDef("consumer", FieldConsumer.class, Scope.SINGLETON,
                        injectionNames(fieldIps), fieldIps, null, null, 0)
        )));
        FieldConsumer c = (FieldConsumer) beans.getBean("consumer");
        assertNotNull(c);
        assertNotNull(c.service, "@Inject 字段未注入");
        assertEquals("b", c.service.tag(),
                "字段 @Named(\"b\") 应注入 name=b 的 bean");
    }

    // ───────────────────────────────────────────────────────────────
    // 3. 同类型多 bean 消歧(DAO 场景复刻)
    // ───────────────────────────────────────────────────────────────
    @Test
    void daoScenario_sameTypeMultipleBeans() throws Exception {
        // 模拟 DAO:stylistViewDao / serviceCategoryViewDao 都是 Dao 接口
        // UserService 用 @Named 各取一个,验证两边实例不同(没按类型混)
        // 用 @Bean 工厂方法而不是无参 ctor,这样 Dao.name 才能区分两个 bean 实例
        java.lang.reflect.Method mStylist = DaoConfig.class.getDeclaredMethod("stylistViewDao");
        java.lang.reflect.Method mSvcCat = DaoConfig.class.getDeclaredMethod("serviceCategoryViewDao");
        List<InjectionPoint> fieldIps = fieldInjections(UserService.class);
        commit(new ArrayList<>(List.of(
                new BeanDef("daoConfig", DaoConfig.class, Scope.SINGLETON,
                        null, null, null, null, 0),
                new BeanDef("stylistViewDao", Dao.class, Scope.SINGLETON,
                        List.of("daoConfig"), null, null, null, 0, mStylist, "daoConfig"),
                new BeanDef("serviceCategoryViewDao", Dao.class, Scope.SINGLETON,
                        List.of("daoConfig"), null, null, null, 0, mSvcCat, "daoConfig"),
                new BeanDef("userService", UserService.class, Scope.SINGLETON,
                        injectionNames(fieldIps), fieldIps, null, null, 0)
        )));
        UserService us = (UserService) beans.getBean("userService");
        assertNotNull(us);
        assertEquals("stylistViewDao", us.stylistViewDao().name(),
                "stylistViewDao 字段应取名为 stylistViewDao 的 bean");
        assertEquals("serviceCategoryViewDao", us.serviceCategoryViewDao().name(),
                "serviceCategoryViewDao 字段应取名为 serviceCategoryViewDao 的 bean");
        assertNotSame(us.stylistViewDao(), us.serviceCategoryViewDao(),
                "同类型两个 bean 必须注入不同实例(不能混)");
    }

    // ───────────────────────────────────────────────────────────────
    // 4. 缺名异常(@Named 不存在 → 抛 NoSuchBeanException)
    // ───────────────────────────────────────────────────────────────
    @Test
    void ctorMissingNameThrows() {
        assertThrows(NoSuchBeanException.class, () -> {
            commit(List.of(
                    new BeanDef("a", Service.class, Scope.SINGLETON, null, null, null, null, 0),
                    new BeanDef("consumer", MissingNameConsumer.class, Scope.SINGLETON,
                            null, null, null, null, 0)
            ));
            beans.getBean("consumer");
        });
    }

    // ───────────────────────────────────────────────────────────────
    // 5. @Named + @Optional:容器没注册时返回 null,不抛
    // ───────────────────────────────────────────────────────────────
    @Test
    void ctorNamedWithOptionalReturnsNullWhenAbsent() {
        // 容器只注册 a,consumer 构造器 @Named("missing") + @Optional → 应降级为 null
        commit(List.of(
                new BeanDef("a", Service.class, Scope.SINGLETON, null, null, null, null, 0),
                new BeanDef("consumer", OptionalNamedConsumer.class, Scope.SINGLETON,
                        null, null, null, null, 0)
        ));
        OptionalNamedConsumer c = (OptionalNamedConsumer) beans.getBean("consumer");
        assertNotNull(c);
        assertNull(c.service(),
                "@Named(\"missing\") + @Optional 缺失时返回 null");
    }

    // ───────────────────────────────────────────────────────────────
    // 6. 类型兜底(零回归:无 @Named 继续按类型解析)
    // ───────────────────────────────────────────────────────────────
    @Test
    void typeFallback_noRegression() throws Exception {
        // 字段 @Inject 无 @Named + 容器只有唯一 Service bean → 应按类型拿到
        List<InjectionPoint> fieldIps = fieldInjections(TypeFallbackConsumer.class);
        commit(List.of(
                new BeanDef("onlyService", Service.class, Scope.SINGLETON,
                        null, null, null, null, 0),
                new BeanDef("consumer", TypeFallbackConsumer.class, Scope.SINGLETON,
                        injectionNames(fieldIps), fieldIps, null, null, 0)
        ));
        TypeFallbackConsumer c = (TypeFallbackConsumer) beans.getBean("consumer");
        assertNotNull(c);
        assertEquals("onlyService", c.service.tag(),
                "无 @Named + 唯一类型 → 按类型解析(零回归)");
    }

    // ───────────────────────────────────────────────────────────────
    // 7. setter 按名(@Inject 方法的参数级别 @Named)
    // ───────────────────────────────────────────────────────────────
    @Test
    void setterByName() throws Exception {
        // 字段名 setterService 是 @Inject 方法,参数带 @Named("b")
        java.lang.reflect.Method setter =
                SetterConsumer.class.getDeclaredMethod("setterService", Service.class);
        List<InjectionPoint> methodIps = List.of(InjectionPoint.method(setter, null, null));
        commit(new ArrayList<>(List.of(
                new BeanDef("a", Service.class, Scope.SINGLETON, null, null, null, null, 0),
                new BeanDef("b", Service.class, Scope.SINGLETON, null, null, null, null, 0),
                new BeanDef("consumer", SetterConsumer.class, Scope.SINGLETON,
                        injectionNames(methodIps), methodIps, null, null, 0)
        )));
        SetterConsumer c = (SetterConsumer) beans.getBean("consumer");
        assertNotNull(c);
        assertEquals("b", c.service.tag(),
                "setter 参数 @Named(\"b\") 应注入 b");
    }

    // ───────────────────────────────────────────────────────────────
    // 8. 构造器 @Named + 同类型多 bean —— 必须走"按名拓扑"而非"按类型拓扑"
    //    (复现 BeanFactory + 多 JdbcViewDao 场景,验证 computeInjectionNames 不会按类型误指)
    // ───────────────────────────────────────────────────────────────
    @Test
    void ctorNamed_respectsNamedOverTypeInTopology() throws Exception {
        // 3 个同类型 Dao bean;Consumer ctor 参数 @Named("servicesEntityViewDao")
        // 如果 computeInjectionNames 只按类型找,会拿到遍历顺序首个 (假设 "stylistViewDao"),
        // 把 consumer 拓扑到 stylistViewDao 之后 → servicesEntityViewDao 还没 instantiate →
        // ctor @Named 查不到 → NoSuchBeanException
        java.lang.reflect.Method mStylist = MultiDaoConfig.class.getDeclaredMethod("stylistViewDao");
        java.lang.reflect.Method mSvcCat = MultiDaoConfig.class.getDeclaredMethod("serviceCategoryViewDao");
        java.lang.reflect.Method mSvc = MultiDaoConfig.class.getDeclaredMethod("servicesEntityViewDao");
        // 注意:这里的 injectionNames = null —— 模拟 AppContext.buildBeanDef 的产物,
        // 测试"完全依赖 enrichInjectionNames 反射 ctor 参数"这条路径
        commit(new ArrayList<>(List.of(
                new BeanDef("multiDaoConfig", MultiDaoConfig.class, Scope.SINGLETON,
                        null, null, null, null, 0),
                new BeanDef("stylistViewDao", Dao.class, Scope.SINGLETON,
                        List.of("multiDaoConfig"), null, null, null, 0, mStylist, "multiDaoConfig"),
                new BeanDef("serviceCategoryViewDao", Dao.class, Scope.SINGLETON,
                        List.of("multiDaoConfig"), null, null, null, 0, mSvcCat, "multiDaoConfig"),
                new BeanDef("servicesEntityViewDao", Dao.class, Scope.SINGLETON,
                        List.of("multiDaoConfig"), null, null, null, 0, mSvc, "multiDaoConfig"),
                new BeanDef("consumer", CtorNamedConsumer.class, Scope.SINGLETON,
                        null, null, null, null, 0)
        )));
        CtorNamedConsumer c = (CtorNamedConsumer) beans.getBean("consumer");
        assertNotNull(c);
        assertEquals("servicesEntityViewDao", c.dao().name(),
                "ctor @Named(\"servicesEntityViewDao\") 必须注入名为 servicesEntityViewDao 的 bean,"
                        + " 不能被同类型多 bean 的类型查找误指到第一个");
    }

    // ─── 测试用 POJO ───

    public static class Service implements BeanNameAware {
        private String tag = "default";
        @Override
        public void setBeanName(String name) {
            this.tag = name;                                       // 记录 bean 名,断言时区分 a/b
        }
        public String tag() { return tag; }
    }

    public static class Consumer {
        private final Service service;
        public Consumer(@Named("a") Service service) { this.service = service; }
        public Service service() { return service; }
    }

    public static class FieldConsumer {
        @Inject @Named("b")
        private Service service;
    }

    public static class Dao {
        private final String name;
        public Dao() { this.name = ""; }
        public Dao(String name) { this.name = name; }
        public String name() { return name; }
    }

    public static class UserService {
        @Inject @Named("stylistViewDao")
        private Dao stylistViewDao;

        @Inject @Named("serviceCategoryViewDao")
        private Dao serviceCategoryViewDao;

        public Dao stylistViewDao() { return stylistViewDao; }
        public Dao serviceCategoryViewDao() { return serviceCategoryViewDao; }
    }

    public static class MissingNameConsumer {
        private final Service service;
        public MissingNameConsumer(@Named("missing") Service service) {
            this.service = service;
        }
        public Service service() { return service; }
    }

    public static class OptionalNamedConsumer {
        private final Service service;
        public OptionalNamedConsumer(@Named("missing") @Optional Service service) {
            this.service = service;
        }
        public Service service() { return service; }
    }

    public static class TypeFallbackConsumer {
        @Inject
        private Service service;
    }

    public static class SetterConsumer {
        private Service service;
        @Inject
        public void setterService(@Named("b") Service service) {
            this.service = service;
        }
    }

    /** DAO 工厂配置 —— 用 @Bean 工厂方法区分同名同类型两个 bean 实例。 */
    public static class DaoConfig {
        @Bean(name = "stylistViewDao")
        public Dao stylistViewDao() {
            return new Dao("stylistViewDao");
        }

        @Bean(name = "serviceCategoryViewDao")
        public Dao serviceCategoryViewDao() {
            return new Dao("serviceCategoryViewDao");
        }
    }

    /** 3 个同类型 Dao + 单 ctor @Named 消费 —— 复现 BeanFactory + JdbcViewDao 拓扑场景。 */
    public static class MultiDaoConfig {
        @Bean(name = "stylistViewDao")
        public Dao stylistViewDao() {
            return new Dao("stylistViewDao");
        }

        @Bean(name = "serviceCategoryViewDao")
        public Dao serviceCategoryViewDao() {
            return new Dao("serviceCategoryViewDao");
        }

        @Bean(name = "servicesEntityViewDao")
        public Dao servicesEntityViewDao() {
            return new Dao("servicesEntityViewDao");
        }
    }

    /** 构造器 @Named 注入 —— 不带 @Inject (单 ctor 默认按类型,但 @Named 显式按名)。 */
    public static class CtorNamedConsumer {
        private final Dao dao;
        public CtorNamedConsumer(@Named("servicesEntityViewDao") Dao dao) {
            this.dao = dao;
        }
        public Dao dao() { return dao; }
    }
}