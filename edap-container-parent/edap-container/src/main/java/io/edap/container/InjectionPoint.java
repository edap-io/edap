package io.edap.container;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * 注入点：字段注入 / 方法注入（setter-style）的统一封装。
 *
 * 构造期被 BeanDef 持有，Phase 2 注入时按 isField() 分支执行：
 *   - 字段：f.set(instance, dep)
 *   - 方法：m.invoke(instance, args)  // args 按方法参数解析
 *
 * beanName() / requiredType()：
 *   - 字段：beanName 由 @Inject("name") 指定；无则按 requiredType 解析
 *   - 方法：beanName 来自 @Inject("name")（setter-style 通常按 parameter 推导）
 */
public final class InjectionPoint {

    public enum Kind { FIELD, METHOD }

    private final Kind       kind;
    private final Field      field;          // kind == FIELD 时非 null
    private final Method     method;         // kind == METHOD 时非 null
    private final String     beanName;       // 直接绑定的 bean 名（@Inject("xxx")）；可能 null
    private final Class<?>   requiredType;   // 依赖类型（按类型解析时用）

    public static InjectionPoint field(Field f, String beanName, Class<?> requiredType) {
        return new InjectionPoint(Kind.FIELD, f, null, beanName, requiredType);
    }

    public static InjectionPoint method(Method m, String beanName, Class<?> requiredType) {
        return new InjectionPoint(Kind.METHOD, null, m, beanName, requiredType);
    }

    private InjectionPoint(Kind kind, Field field, Method method,
                           String beanName, Class<?> requiredType) {
        this.kind         = kind;
        this.field        = field;
        this.method       = method;
        this.beanName     = beanName;
        this.requiredType = requiredType;
    }

    public boolean  isField()      { return kind == Kind.FIELD; }
    public boolean  isMethod()     { return kind == Kind.METHOD; }
    public Field    field()        { return field; }
    public Method   method()       { return method; }
    public String   beanName()     { return beanName; }
    public Class<?> requiredType() { return requiredType; }
}