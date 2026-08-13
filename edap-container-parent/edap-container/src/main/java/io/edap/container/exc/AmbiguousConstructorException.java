package io.edap.container.exc;

import java.lang.reflect.Constructor;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 构造器选择歧义（多个 {@code @Inject} 构造器同时存在）——{@code BeanContainer.selectConstructor} 阶段。
 *
 * <p>对应 §4.5.4.10.1 规则 3：bean 有多个构造器且多个标注了 {@code @Inject} 时抛出。
 * 强制 bean 作者显式消歧（删掉多余的 {@code @Inject} 标注，或用 {@code @Inject(required=false)} /
 * {@code Optional<T>} 表达可选依赖）。</p>
 *
 * <p>与 {@link NoSuitableConstructorException} 一致——继承 {@link RuntimeException}，
 * 不污染 {@link io.edap.container.BeanContainer#selectConstructor} 等方法签名。</p>
 *
 * <p><b>失败语义</b>：抛出后 {@code BeanContainer.instantiate} 进入 catch 转
 * {@code BeanInstantiationException} 冒泡到 {@code AppContext.start}，
 * 最终 deploy 返回 fail(104)。</p>
 */
public class AmbiguousConstructorException extends RuntimeException {

    private final Class<?>              beanClass;
    private final List<Constructor<?>>  candidates;

    /**
     * @param beanClass  引发歧义的 bean 类型
     * @param candidates 所有 {@code @Inject} 标注的构造器（至少 2 个）
     */
    public AmbiguousConstructorException(Class<?> beanClass, List<Constructor<?>> candidates) {
        super(buildMessage(beanClass, candidates));
        this.beanClass  = beanClass;
        this.candidates = List.copyOf(candidates);
    }

    /** 引发歧义的 bean 类型。 */
    public Class<?> beanClass()                       { return beanClass; }

    /** 候选 {@code @Inject} 构造器列表（不可变副本）。 */
    public List<Constructor<?>> candidates()          { return candidates; }

    /**
     * 组装 message：列出 bean 名 + 每个候选构造器签名 + 参数简要列表。
     */
    private static String buildMessage(Class<?> beanClass, List<Constructor<?>> candidates) {
        StringBuilder sb = new StringBuilder("Ambiguous constructors for ")
                .append(beanClass.getName())
                .append(" — multiple @Inject-annotated constructors found (")
                .append(candidates.size())
                .append("):");
        for (Constructor<?> c : candidates) {
            sb.append("\n  - ").append(c.toString());
        }
        sb.append("\nResolve by removing redundant @Inject (single-constructor auto-detect otherwise).");
        return sb.toString();
    }
}
