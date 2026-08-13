package io.edap.microservice.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标记一个依赖是可选的——容器在 {@code BeanContainer.ctorArgs}（§4.5.4.10.2 规则 b）
 * 中遇到 {@code @Optional} 标注的参数时，若对应 bean 缺失则注入 {@code null}，而非
 * 抛 {@code NoSuchBeanException}。
 *
 * <p>这是 edap 容器对"可选依赖"的官方标记。原因：{@code javax.inject.Inject} 是
 * JSR-330 的 marker annotation，没有 {@code required} 属性；{@code Spring @Autowired}
 * 的 {@code required=false} 又无法脱离 Spring 上下文单独使用。edap 选择新增
 * 专属注解来表达"可选"语义——用法与 {@code @Inject(required=false)} 完全等价，零歧义。</p>
 *
 * <p><b>使用方式</b>：</p>
 * <pre>{@code
 * public class MyService {
 *     public MyService(@Optional EmailSender sender) {
 *         // sender 可能为 null，调用方需做 null 判断
 *     }
 *
 *     // 等价写法（更"现代"）：
 *     public MyService(Optional<EmailSender> sender) {
 *         // sender 永远是 Optional，找不到 → Optional.empty()
 *     }
 * }
 * }</pre>
 *
 * <p><b>作用域</b>：构造器参数 / 方法参数 / 字段。三者优先级：
 * <ol>
 *   <li>{@code Optional<T>}（类型）—— 容器内部按"内层类型解析 + 缺失→empty"处理
 *       （§4.5.4.10.2 规则 a）</li>
 *   <li>{@code @Optional}（注解）—— 缺失→null（§4.5.4.10.2 规则 b）</li>
 *   <li>{@code @Inject}（默认）—— 缺失→抛 {@code NoSuchBeanException}
 *       （§4.5.4.10.2 规则 c）</li>
 * </ol></p>
 */
@Target({ElementType.PARAMETER, ElementType.FIELD, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface Optional {
}