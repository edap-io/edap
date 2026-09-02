/*
 * Copyright 2023 The edap Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package io.edap.container.transactional;

import io.edap.container.BeanPostProcessor;
import io.edap.tx.annotation.ManualTransaction;
import io.edap.tx.annotation.Transactional;

import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * {@link BeanPostProcessor} 实现:扫描 bean 上的 {@link Transactional @Transactional} 注解,
 * 对含注解的 bean 生成 ASM wrapper,在 init 之后替换容器中的实例。
 *
 * <p><b>触发时机</b>:{@link #postProcessAfterInit} —— 必须等 {@code @PostConstruct}
 * 执行完毕,wrapper 才把 init 后的实例包起来。</p>
 *
 * <p><b>类筛选</b>:从 bean 的类层级 BFS 收集所有接口,挑出第一个含 @Transactional 方法
 * 的接口作为 wrapper 实现目标。多接口含 @Transactional 的情况 Phase 3 不支持
 * (业务上罕见;Phase 4 加配置项指定主接口)。</p>
 *
 * <p><b>不触发条件</b>:</p>
 * <ul>
 *   <li>类层级无任何 @Transactional 注解(类上 + 方法上)</li>
 *   <li>类是 final(JDK Proxy + ASM 都需要非 final)</li>
 * </ul>
 *
 * <p><b>依赖</b>:不再需要 {@code TransactionManagerResolver} —— TM 走全局静态
 * {@link io.edap.tx.TransactionManagers} 注册表(由 {@link io.edap.container.AppContext}
 * 在启动期调 {@code TransactionManagers.register(name, tm)} 填充)。BPP 只负责
 * wrap 逻辑 + 类加载。生成器需要的 ClassLoader 用 bean 自身的 ClassLoader
 * (沿用 {@code HandlerAsmGenerator} 的同包加载模式)。</p>
 *
 * <p><b>多 TM 路由</b>:同一 bean 上不同方法可指定不同 tm(通过
 * {@code @ManualTransaction(transactionManager="...")}),wrapper 按方法在
 * {@code <clinit>} 期 inline 查 {@link io.edap.tx.TransactionManagers#get}
 * 把结果缓存到 static final {@code TM_<i>} 字段,运行期只 GETSTATIC。</p>
 */
public class TransactionalBeanPostProcessor implements BeanPostProcessor {

    public TransactionalBeanPostProcessor() {
    }

    @Override
    public Object postProcessAfterInit(Object bean, String beanName) {
        if (bean == null) {
            return bean;
        }
        Class<?> beanClass = bean.getClass();
        if (hasTransactionalAnnotation(beanClass)) {
            return wrapBean(bean, beanClass);
        }
        return bean;
    }

    /** 类层 + 方法层扫一遍:只要存在 @Transactional 或 @ManualTransaction(类或方法上)就触发 wrapper。
     *
     * <p><b>实现类方法上的注解也算</b>:业务方把 @Transactional 写在 impl 而非接口,
     * 接口由 edap / proto 自动生成、不可改的场景(如 {@code OrderServiceImpl#create}),
     * BPP 也必须识别 — 否则 hasTransactionalAnnotation 误判为无注解,跳过 wrap,
     * 方法直接调原实例,事务不开启。</p>
     */
    private boolean hasTransactionalAnnotation(Class<?> beanClass) {
        // 1. 类层级(beanClass + 父类)
        Class<?> c = beanClass;
        while (c != null && c != Object.class) {
            if (c.isAnnotationPresent(Transactional.class) ||
                c.isAnnotationPresent(ManualTransaction.class)) {
                return true;
            }
            c = c.getSuperclass();
        }
        // 2. 接口层级(接口自身 + 父接口,递归)
        for (Class<?> iface : collectInterfaces(beanClass)) {
            for (Method m : collectInterfaceMethods(iface)) {
                if (m.isAnnotationPresent(Transactional.class) ||
                    m.isAnnotationPresent(ManualTransaction.class)) {
                    return true;
                }
            }
        }
        // 3. 实现类方法层级(beanClass + 父类上的方法,含 override 父类方法)
        c = beanClass;
        while (c != null && c != Object.class) {
            for (Method m : c.getDeclaredMethods()) {
                if (m.isAnnotationPresent(Transactional.class) ||
                    m.isAnnotationPresent(ManualTransaction.class)) {
                    return true;
                }
            }
            c = c.getSuperclass();
        }
        return false;
    }

    /**
     * 包装 bean:找含 @Transactional 方法的接口,生成 wrapper。
     *
     * <p>final class 直接抛 IllegalStateException —— wrapper 是 ASM 生成的子类,
     * 不能 extends final。</p>
     */
    private Object wrapBean(Object bean, Class<?> beanClass) {
        if (java.lang.reflect.Modifier.isFinal(beanClass.getModifiers())) {
            throw new IllegalStateException(
                    "Cannot wrap final class with @Transactional/@ManualTransaction: " + beanClass.getName()
                            + " (wrapper needs to extend the bean class — JDK Proxy + ASM require non-final)");
        }
        Class<?> txInterface = pickTransactionalInterface(beanClass);
        if (txInterface == null) {
            // 类上有 @Transactional 但接口方法无注解 —— 罕见,Phase 3 不支持
            throw new IllegalStateException(
                    "Bean " + beanClass.getName()
                            + " has @Transactional/@ManualTransaction on class but no annotated method on any interface");
        }

        // 冲突检查:同一方法上同时存在 @Transactional 和 @ManualTransaction → 报错
        checkAnnotationConflict(txInterface, beanClass);

        List<TransactionalClassGenerator.MethodSpec> specs =
                TransactionalClassGenerator.scanTransactional(txInterface, beanClass);
        if (specs.isEmpty()) {
            return bean;
        }

        TransactionalClassGenerator gen = new TransactionalClassGenerator(txInterface, specs);
        ClassLoader loader = beanClass.getClassLoader();
        if (loader == null) {
            loader = Thread.currentThread().getContextClassLoader();
        }
        return gen.instantiate(loader, bean);
    }

    /**
     * 从类层级 BFS 收集所有接口,挑出第一个含 @Transactional 方法的接口。
     * Phase 3 MVP:多接口含注解时只挑第一个;Phase 4 可加 {@code @Transactional("primaryIf=...")}
     * 或在 bean 定义里指定主接口。
     *
     * <p><b>实现类方法的注解也算</b>:接口由 edap 自动生成、不可改,业务方只能在 impl
     * 加 @Transactional 的场景下,挑接口的标准是"接口方法本身 OR impl 上 override 该
     * 接口方法的方法"上存在 tx 注解。</p>
     */
    private Class<?> pickTransactionalInterface(Class<?> beanClass) {
        for (Class<?> iface : collectInterfaces(beanClass)) {
            for (Method im : collectInterfaceMethods(iface)) {
                if (im.isAnnotationPresent(Transactional.class) ||
                    im.isAnnotationPresent(ManualTransaction.class)) {
                    return iface;
                }
                Method implMethod = findImplOverride(beanClass, im);
                if (implMethod != null &&
                        (implMethod.isAnnotationPresent(Transactional.class) ||
                         implMethod.isAnnotationPresent(ManualTransaction.class))) {
                    return iface;
                }
            }
        }
        return null;
    }

    /**
     * 检查注解互斥 — 启动期 fail-fast。三种冲突:
     * <ol>
     *   <li><b>类级互斥</b>:impl 类层级(beanClass + 父类)上同时存在
     *       {@code @Transactional} 和 {@code @ManualTransaction};</li>
     *   <li><b>类级 + 方法级冲突</b>:类级 {@code @Transactional} 配上方法级
     *       {@code @ManualTransaction}(或反之);</li>
     *   <li><b>方法级自身互斥</b>:同一接口方法的接口自身注解 + impl override 注解中
     *       同时存在 {@code @Transactional} 和 {@code @ManualTransaction}。</li>
     * </ol>
     * 类级 + 方法级组合的设计意图:类级注解 = 该类所有方法的默认配置(Spring 语义),
     * 方法级注解 = override 类级。若类级和方法级选了不同"管法"(wrapper 管 vs 业务管),
     * 是没有合理语义的,启动期直接报错。
     */
    private void checkAnnotationConflict(Class<?> txInterface, Class<?> beanClass) {
        // 1. 类级互斥
        Transactional txOnClass = findClassLevelAnnotation(beanClass, Transactional.class);
        ManualTransaction manualTxOnClass = findClassLevelAnnotation(beanClass, ManualTransaction.class);
        if (txOnClass != null && manualTxOnClass != null) {
            throw new IllegalStateException(
                    "Bean " + beanClass.getName()
                            + " has both @Transactional and @ManualTransaction on class level "
                            + "(or via parent class) — they are mutually exclusive "
                            + "(one means wrapper-managed, the other means business-managed)");
        }

        // 2 + 3. 类级+方法级冲突 + 方法级自身互斥
        for (Method im : collectInterfaceMethods(txInterface)) {
            Transactional txOnIface = im.getAnnotation(Transactional.class);
            ManualTransaction manualTxOnIface = im.getAnnotation(ManualTransaction.class);
            Method implMethod = findImplOverride(beanClass, im);
            Transactional txOnImpl = implMethod != null ? implMethod.getAnnotation(Transactional.class) : null;
            ManualTransaction manualTxOnImpl = implMethod != null ? implMethod.getAnnotation(ManualTransaction.class) : null;
            boolean hasTxOnMethod = txOnIface != null || txOnImpl != null;
            boolean hasManualOnMethod = manualTxOnIface != null || manualTxOnImpl != null;

            // 类级 + 方法级冲突
            if (txOnClass != null && hasManualOnMethod) {
                throw new IllegalStateException(
                        "Method " + txInterface.getName() + "#" + im.getName()
                                + " has class-level @Transactional but method-level @ManualTransaction "
                                + "(interface=" + (manualTxOnIface != null)
                                + ", impl=" + (manualTxOnImpl != null)
                                + ") — class-level and method-level use different management strategies");
            }
            if (manualTxOnClass != null && hasTxOnMethod) {
                throw new IllegalStateException(
                        "Method " + txInterface.getName() + "#" + im.getName()
                                + " has class-level @ManualTransaction but method-level @Transactional "
                                + "(interface=" + (txOnIface != null)
                                + ", impl=" + (txOnImpl != null)
                                + ") — class-level and method-level use different management strategies");
            }

            // 方法级自身互斥
            if (hasTxOnMethod && hasManualOnMethod) {
                throw new IllegalStateException(
                        "Method " + txInterface.getName() + "#" + im.getName()
                                + " has both @Transactional and @ManualTransaction "
                                + "(interface=" + (txOnIface != null || manualTxOnIface != null)
                                + ", impl=" + (txOnImpl != null || manualTxOnImpl != null)
                                + ") — they are mutually exclusive "
                                + "(one means wrapper-managed, the other means business-managed)");
            }
        }
    }

    /**
     * 在 {@code beanClass} 层级(impl 类 + 父类,沿继承链向上)查找第一个出现的指定注解。
     * 与 {@link TransactionalClassGenerator#findClassLevelAnnotation} 同语义 ——
     * BPP 和 generator 各自持一份(同包可读,但不放 public API)。
     */
    private static <A extends java.lang.annotation.Annotation> A findClassLevelAnnotation(
            Class<?> beanClass, Class<A> annotationType) {
        Class<?> c = beanClass;
        while (c != null && c != Object.class) {
            A ann = c.getAnnotation(annotationType);
            if (ann != null) return ann;
            c = c.getSuperclass();
        }
        return null;
    }

    /**
     * 在 {@code beanClass} 上找 override {@code ifaceMethod} 的方法。
     * 走 {@link Class#getMethod} —— JVM 会沿继承链向上找最具体的 override。
     *
     * @return impl 方法,或 {@code null}(接口方法在 impl 没找到 —— 通常不该发生,
     *         JVM 字节码验证阶段就拒绝;但防御性返回 null,调用方自行处理)
     */
    private static Method findImplOverride(Class<?> beanClass, Method ifaceMethod) {
        try {
            Method m = beanClass.getMethod(ifaceMethod.getName(), ifaceMethod.getParameterTypes());
            // getMethod 可能返回接口自己声明的方法(若 impl 没显式 override),这种不算 "impl 上的方法"
            if (m.getDeclaringClass() == ifaceMethod.getDeclaringClass()
                    || m.getDeclaringClass().isInterface()) {
                return null;
            }
            return m;
        } catch (NoSuchMethodException e) {
            return null;
        }
    }

    /**
     * 递归收集接口自身 + 所有父接口声明的方法(去重 by name+paramTypes)。
     * {@link Class#getDeclaredMethods()} 不会返回父接口的方法 —— 直接调会漏掉
     * 父接口上的 @Transactional。
     */
    private static Set<Method> collectInterfaceMethods(Class<?> iface) {
        Set<Method> seen = new HashSet<>();
        Deque<Class<?>> queue = new ArrayDeque<>();
        queue.add(iface);
        while (!queue.isEmpty()) {
            Class<?> c = queue.poll();
            if (c == null || c.isInterface() == false) continue;
            for (Method m : c.getDeclaredMethods()) {
                if (!seen.add(m)) continue;
                // seen.add 返回 false 表示重复 —— 已记录过同名同参方法(子类优先)
            }
            for (Class<?> sup : c.getInterfaces()) {
                queue.add(sup);
            }
        }
        return seen;
    }

    /** BFS 收集类 + 所有父类 + 所有接口(去重)。 */
    private static Set<Class<?>> collectInterfaces(Class<?> beanClass) {
        Set<Class<?>> seen = new HashSet<>();
        Deque<Class<?>> queue = new ArrayDeque<>();
        queue.add(beanClass);
        while (!queue.isEmpty()) {
            Class<?> c = queue.poll();
            if (c == null || seen.contains(c)) continue;
            seen.add(c);
            Class<?> sup = c.getSuperclass();
            if (sup != null && sup != Object.class) {
                queue.add(sup);
            }
            for (Class<?> iface : c.getInterfaces()) {
                queue.add(iface);
            }
        }
        return seen;
    }
}