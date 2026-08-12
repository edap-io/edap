package io.edap.container.exc;

/**
 * Bean 注入失败异常（Phase 2 COMMITTING 阶段）。
 *
 * 触发点：BeanContainer.injectDependencies 在字段注入（@Inject 字段）或方法注入（@Inject setter）
 * 抛 IllegalAccessException / InvocationTargetException 时包装抛出。
 *
 * 三段构造：(beanName, memberName, cause) — beanName 用于定位 bean；memberName 用于定位失败的字段或方法；
 * cause 是反射层异常。
 */
public class BeanInjectFailedException extends RuntimeException {

    public BeanInjectFailedException(String beanName, String memberName, Throwable cause) {
        super("注入失败: bean=" + beanName + ", member=" + memberName, cause);
    }
}