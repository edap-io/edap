package io.edap.container;

/**
 * Bean 生命周期钩子基接口。注册到 {@link AppContext#postProcessors()} 后，
 * 在 BeanContainer COMMITTING 阶段（Phase 2）的对应节点被回调，可读 / 改 bean 实例。
 *
 * <p>与 Aware 接口的区别：Aware 是"被注入"语义（bean 实现 Aware 接口 → setter 被调一次），
 * BPP 是"批量处理"语义（M 个 BPP 处理 N 个 bean → 每个 bean 走 M 个回调）。
 * 典型用途：AOP 织入、注解驱动的 wrapper 包装（{@code @Transactional / @RateLimit}）。</p>
 *
 * <p><b>当前状态</b>：接口已定义，BeanContainer 实际调用链尚未接通（见 §4.5 + §九 扩展点）。
 * AppContext.addBeanPostProcessor() 已可用——后续 BeanContainer 接通后即生效。</p>
 */
public interface BeanPostProcessor {

    /**
     * Bean 初始化前回调（{@code @PostConstruct} 之前）。
     * @param bean     当前实例（已经过 injectDependencies，字段已注入）
     * @param beanName Bean 名
     * @return 继续处理的 bean（返回原 bean = 不变；返回新对象 = 替换为 wrapper）
     */
    default Object postProcessBeforeInit(Object bean, String beanName) {
        return bean;
    }

    /**
     * Bean 初始化后回调（{@code @PostConstruct} 之后）。
     * @param bean     当前实例
     * @param beanName Bean 名
     * @return 最终 bean（返回原 bean = 不变；返回新对象 = 替换为最终 wrapper，典型 AOP 织入点）
     */
    default Object postProcessAfterInit(Object bean, String beanName) {
        return bean;
    }
}