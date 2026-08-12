package io.edap.container.exc;

/**
 * 路由绑定失败（Container.bindAll / AppContext.generateHandler 阶段）。
 *
 * <p>统一承载"RouteEntry + bean + Method → 协议 typed Handler"桥接过程中的所有可恢复异常——
 * 主要包括反射层（NoSuchMethodException / ClassNotFoundException / SecurityException /
 * ReflectiveOperationException）与 ASM 生成层（字节码生成失败 / 类加载失败 / 实例化失败）两类。</p>
 *
 * <p>与 {@link NoSuchBeanException} 一致——继承 {@link RuntimeException}，不污染
 * {@link io.edap.container.Container#bindAll} / {@link io.edap.container.AppContext#generateHandler}
 * 的方法签名。</p>
 *
 * <p><b>失败语义</b>：抛出后 {@code Container.bindAll} 的临时 List 随栈帧释放，
 * RouterHub.4 份 List 仍为空——AppContext 整体不进 registry；deploy 返回 fail(104)。</p>
 */
public class RouteBindException extends RuntimeException {

    private final Object     bean;
    private final String     methodName;
    private final Class<?>[] paramTypes;

    /**
     * @param bean       bean 实例（已实例化；用于 message 显示 bean 类名 + 诊断时定位 bean）
     * @param methodName RouteEntry 上的方法名（来自 EAR 扫描 / proto 解析）
     * @param paramTypes 方法参数类型列表（与 RouteEntry.methodName 配对；空数组代表无参）
     * @param cause      原始异常（NoSuchMethodException / ClassNotFoundException /
     *                   SecurityException / ReflectiveOperationException / ASM 生成异常 等）
     */
    public RouteBindException(Object bean, String methodName, Class<?>[] paramTypes, Throwable cause) {
        super(buildMessage(bean, methodName, paramTypes, cause), cause);
        this.bean       = bean;
        this.methodName = methodName;
        this.paramTypes = (paramTypes != null) ? paramTypes.clone() : new Class<?>[0];
    }

    /** bean 实例（用于诊断 / 日志）。 */
    public Object bean()                  { return bean; }

    /** RouteEntry 上的方法名。 */
    public String methodName()            { return methodName; }

    /** 方法参数类型列表（不可变副本）。 */
    public Class<?>[] paramTypes()        { return paramTypes.clone(); }

    /**
     * 组装 message：{@code "Route bind failed: bean=<class>.<simpleName>, method=<methodName>(<paramTypes>), cause=<exception class>: <msg>"}
     */
    private static String buildMessage(Object bean, String methodName, Class<?>[] paramTypes, Throwable cause) {
        String beanDesc  = (bean != null) ? bean.getClass().getName() : "<null>";
        String params    = paramTypesDesc(paramTypes);
        String causeDesc = (cause != null)
                ? cause.getClass().getSimpleName() + ": " + cause.getMessage()
                : "<null>";
        return "Route bind failed: bean=" + beanDesc
                + ", method=" + methodName + "(" + params + ")"
                + ", cause=" + causeDesc;
    }

    /**
     * 参数类型列表的可读形式：{@code "String, long, com.example.Foo"}
     */
    private static String paramTypesDesc(Class<?>[] paramTypes) {
        if (paramTypes == null || paramTypes.length == 0) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < paramTypes.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(paramTypes[i] != null ? paramTypes[i].getName() : "null");
        }
        return sb.toString();
    }
}