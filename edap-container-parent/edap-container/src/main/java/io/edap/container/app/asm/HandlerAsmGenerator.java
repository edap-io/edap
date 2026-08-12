package io.edap.container.app.asm;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 用 ASM 字节码生成协议 typed Handler 实现类。
 *
 * <p><b>为什么可以作为静态单例跨 AppContext 共享而不引入 appCL 泄漏</b>：
 * <ul>
 *   <li>ClassWriter 内部缓存（class hierarchy info / constant pool）只引用 java.lang.* 等标准类</li>
 *   <li>不持有任何用户 app 的 Class 引用</li>
 *   <li>因此 HandlerAsmGenerator.INSTANCE 跨多个 AppContext 复用是安全的</li>
 * </ul>
 *
 * <p><b>关键技术</b>：
 * <ul>
 *   <li>ClassWriter flags = COMPUTE_FRAMES | COMPUTE_MAXS（自动算 stack map / max stack）</li>
 *   <li>按 targetIf 派发到协议对应的 emit 模板：
 *     <ul>
 *       <li>HttpHandler          → emitHttpHandle / emitHttpResponseWrite（req.getPathParam + resp.setBody）</li>
 *       <li>WSServiceMsgHandler  → emitWsServiceMsgHandle（直接 invokevirtual bean.handleMsg(msg) 返回 T，无协议响应写入）</li>
 *       <li>ErpcHandler          → emitErpcHandle / emitErpcResponseWrite（req.deserializeBody + resp.serializeBody）</li>
 *       <li>GrpcHandler          → emitGrpcHandle / emitGrpcResponseWrite（同 Erpc，按 FQCN）</li>
 *     </ul>
 *   </li>
 *   <li>shard 字段检查：每个 emit 模板在生成 invokevirtual 前查 entry.shard() == true
 *       → 插入 ShardRegistry.route(beanName, shardKey) 字节码 + 改写 invokevirtual 目标
 *       → shardKey 参数提取（在 protocol args extraction 阶段多留一个 local var）</li>
 *   <li>bean method 调用部分：按 bean method 参数类型硬编码 cast + 直接 invokevirtual</li>
 *   <li>基本类型参数：checkcast 包装类 + 调 intValue()/longValue()/... 解包</li>
 *   <li>基本类型返回值：调包装类 valueOf() 装箱</li>
 *   <li>void 返回值：按协议约定（HTTP/WS/eRPC/gRPC 通常不写响应）</li>
 * </ul>
 *
 * <p><b>当前状态</b>：骨架已建立（INSTANCE + className + generateHandlerClass 占位），
 * 各协议的 emit 模板实现见后续 PR。</p>
 */
public final class HandlerAsmGenerator {

    /** 静态单例：跨所有 AppContext 共享。 */
    public static final HandlerAsmGenerator INSTANCE = new HandlerAsmGenerator();

    private HandlerAsmGenerator() {}

    /**
     * 类名规则：{@code <InterfaceSimpleName>$<methodName>_<paramTypesJoined>__<hash>}
     * ——保证唯一 + 可缓存。
     */
    public String className(Class<?> targetIf, Method method) {
        String paramTypes = Arrays.stream(method.getParameterTypes())
            .map(c -> c == int.class ? "I" : c == long.class ? "J" : c.getName().replace('.', '_'))
            .collect(Collectors.joining("_"));
        int hash = Objects.hash(targetIf.getName(), method.getDeclaringClass().getName(),
                                 method.getName(), paramTypes);
        String ifSimpleName = targetIf.getSimpleName();
        return "io.edap.container.app.gen." + ifSimpleName + "$" + method.getName() + "_"
             + paramTypes + "__" + Integer.toHexString(hash);
    }

    /**
     * 生成协议 typed Handler 实现类的字节码。返回的字节码可被 defineClass 加载。
     *
     * <p>生成的类长这样（以 HttpHandler 为例）：
     * <pre>{@code
     *   public final class HttpHandler$<m>_<p>__<h> implements HttpHandler {
     *       private final <BeanClass> bean;
     *       private final <具体 Entry> entry;
     *       public HttpHandler$<m>_<p>__<h>(<BeanClass> bean, <具体 Entry> entry) { ... }
     *       public void handle(HttpRequest req, HttpResponse resp) throws IOException {
     *           // 1. emitHttpHandle：从 req 按 entry 提参数 → local var 3, 4, ...
     *           // 2. 加载 bean 字段 + 各 local var → 直接 invokevirtual bean.method(...)
     *           // 3. emitHttpResponseWrite：把 bean 返回值写入 resp
     *       }
     *   }
     * }</pre>
     *
     * <p><b>当前状态</b>：占位实现，待 emit 模板逐协议实现后替换。
     * 触发后 AppContext.generateHandler 阶段会抛 RouteBindException（ClassNotFoundException，
     * 因 generateHandlerClass 实际未产出与 className 匹配的字节码）——属于"功能尚未落地"的明确失败，
     * 不静默退化。</p>
     *
     * @param targetIf 协议 typed Handler 接口（HttpHandler / WSServiceMsgHandler / ErpcHandler / GrpcHandler）
     * @param method   bean 上的目标 Method
     * @param entryClass 具体 RouteEntry / GrpcMethodEntry 的 Class（用于 emit 模板决定字节码分支）
     * @param beanClass bean 的 Class（用于 invokevirtual 目标）
     * @return 字节码；当前阶段返回空数组占位
     */
    public byte[] generateHandlerClass(Class<?> targetIf, Method method,
                                       Class<?> entryClass, Class<?> beanClass) {
        // 占位：emit 模板尚未实现；返回空数组。
        // AppContext.generateHandler 阶段调 Class.forName(name, true, genCL) 时会抛 CNFE，
        // 被 RouteBindException 包装（cause = ClassNotFoundException）冒泡到 Container.bindAll，
        // 最终导致 deploy 失败（fail(104)）——这是预期行为，明确告知"功能未落地"。
        return new byte[0];
    }
}