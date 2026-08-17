package io.edap.container.app.asm;

import io.edap.container.Capability;
import io.edap.container.mw.AnnoData;
import io.edap.container.ws.WSServiceMsgHandler;
import io.edap.grpc.GrpcHandler;
import io.edap.http.HttpHandler;
import io.edap.rpc.ErpcHandler;

import java.lang.reflect.Method;
import java.util.List;

import static io.edap.util.StringUtil.toUnderScore;

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
 *   <li>{@code annoData} 驱动的协议参数提取：每个 emit 模板读 annoData.getType()（{@code @ProtoHttp} /
 *       {@code @ProtoWebSocket} / eRPC descriptor ...）决定取哪个字段（{@code path} / {@code method} /
 *       {@code path} 等）。同一方法可能多条 annoData 对应多条 Handler，但每条 Handler 看一条 annoData。</li>
 *   <li>shard 亲和检查：emit 模板看 annoData 旁是否有 {@code @Sharded} —— 调用方（AppContext）在传
 *       annoData 之前已查出"本方法是否 shard 亲和"，传给 HandlerAsmGenerator 用 boolean shard 参数
 *       而非让本类再去翻 pmd.annoDatas（职责单一化）</li>
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
     * 类名规则（binary name，符合 Java 命名约定）：
     * <pre>{@code
     *   <capability>.<lowerCasedProtoIfFQCN>.<UpperFirstMethod>Handler
     * }</pre>
     *
     * <p><b>命名约定</b>：
     * <ul>
     *   <li><b>包名全小写</b>：capability 已小写（{@code http} / {@code ws} / {@code erpc} / {@code grpc}）；
     *       protoIf FQCN 的每段也强制 toLowerCase，避免 {@code com.estylr.api.v1.ReviewService}
     *       的 {@code ReviewService} 大写段违反 Java 包名约定</li>
     *   <li><b>类名首字母大写</b>：method 名称首字母 toUpperCase + {@code Handler} 后缀，
     *       例 {@code list} → {@code ListHandler}，符合 Java 类名约定</li>
     * </ul>
     *
     * <p>三段组成保证唯一：
     * <ul>
     *   <li><b>{@code capability}</b>：targetIf 派生的协议能力，小写子包名</li>
     *   <li><b>{@code lowerCasedProtoIfFQCN}</b>：proto 服务接口 FQCN，每段强制小写。
     *       proto service 内方法不重载 → 「serviceInterface + method」天然唯一</li>
     *   <li><b>{@code UpperFirstMethod + Handler}</b>：method 名称首字母大写 + {@code Handler} 后缀</li>
     * </ul>
     *
     * <p>示例：
     * <ul>
     *   <li>targetIf=HttpHandler, protoIf=com.estylr.api.v1.ReviewService, method=list
     *       → {@code http.com.estylr.api.v1.reviewservice.ListHandler}</li>
     *   <li>targetIf=WSServiceMsgHandler, protoIf=com.x.ChatService, method=onMessage
     *       → {@code ws.com.x.chatservice.OnMessageHandler}</li>
     * </ul>
     *
     * <p><b>ASM 端使用</b>：{@code handlerName} 返回的是 <b>binary name</b>（点号），
     * 传给 ASM {@code ClassWriter.visit(..., internalName, ...)} 前必须经
     * {@code io.edap.util.AsmUtil.toInternalName(...)} 转为 <b>internal name</b>（斜杠），
     * 否则 class 文件的 {@code this_class} 仍是点号形式，JVM 加载时把整串当作单个 class name
     * → {@code ClassFormatError: Illegal class name}（"http.com.estylr...listHandler"
     * 整串无法 split 成合法 package + class）。</p>
     *
     * <p><b>为什么用显式 {@code protoIf} 而非 {@code method.getDeclaringClass()}</b>：
     * Handler class 是"proto 接口 × capability × 方法"的产物，与实现类无关。
     * 同一 proto 接口（{@code ReviewService.list}）可能有多种实现——本地 bean、远端 RPC、
     * 暂未部署——这些情形共享同一份 Handler impl class 字节码，由
     * {@link io.edap.container.app.ClusterShardRouter} 在 dispatch 阶段决定走本地 invokevirtual
     * 还是远端 RPC。用 {@code method.getDeclaringClass()} 会带回实现类（{@code ReviewServiceImpl}），
     * 导致无本地 bean 时无法生成、多实现并存时重复生成同字节码。</p>
     *
     * @param targetIf 协议 typed Handler 接口（HttpHandler / WSServiceMsgHandler / ...）
     * @param protoIf  proto 服务接口（{@code ReviewService.class}），用于 serviceInterface 段；
     *                  <b>注意</b>是接口本身，不是实现类（{@code ReviewServiceImpl}）
     * @param method   proto 接口方法 —— 仅取 {@code method.getName()} 用于类名末段；
     *                  method 本身可来自实现类不影响 className 唯一性
     */
    public static String handlerName(Class<?> targetIf, Class<?> protoIf, Method method) {
        return capabilityOf(targetIf).name().toLowerCase()
             + "."
             + lowerPackageSegments(protoIf)
             + "."
             + upperFirst(method.getName())
             + "Handler";
    }

    /**
     * 把 FQCN 的每段强制小写。例如 {@code com.estylr.api.v1.ReviewService}
     * → {@code com.estylr.api.v1.reviewservice}。
     */
    private static String lowerPackageSegments(Class cls) {
        StringBuilder sb = new StringBuilder();
        sb.append(cls.getPackageName()).append('.');
        sb.append(toUnderScore(cls.getSimpleName()));
        return sb.toString();
    }

    /** 字符串首字母大写。空串 / null 原样返回。 */
    private static String upperFirst(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    /**
     * targetIf → Capability 静态映射。集中在此避免散落 if/else；
     * 未来加新协议 typed Handler（如 KafkaHandler）只需加一行 if。
     */
    private static Capability capabilityOf(Class<?> targetIf) {
        if (targetIf == HttpHandler.class)         return Capability.HTTP;
        if (targetIf == WSServiceMsgHandler.class) return Capability.WS;
        if (targetIf == ErpcHandler.class)         return Capability.ERPC;
        if (targetIf == GrpcHandler.class)         return Capability.GRPC;
        throw new IllegalArgumentException(
                "HandlerAsmGenerator.className: unknown targetIf " + targetIf.getName());
    }

    /**
     * 生成协议 typed Handler 实现类的字节码。返回的字节码可被 defineClass 加载。
     *
     * <p>生成的类长这样（以 HttpHandler 为例）：
     * <pre>{@code
     *   public final class http_com_example_GreeterService_sayHelloHandler implements HttpHandler {
     *       private final BeanContainer beans;
     *       private final Object bean;          // 由构造时从容器查
     *       private final ShardRegistry shards;
     *
     *       public <init>(BeanContainer beans, ShardRegistry shards) {
     *           this.beans  = beans;
     *           this.shards = shards;
     *           BeanWrap bw = beans.findBeanWrapByType(GreeterService.class);
     *           if (bw == null) {
     *               throw new IllegalStateException("No bean for GreeterService");
     *           }
     *           this.bean = bw.instance();
     *       }
     *
     *       public void handle(HttpRequest req, HttpResponse resp) throws IOException {
     *           // 1. emitHttpHandle：从 req 按 annoData 提参数 → local var 3, 4, ...
     *           // 2. shard ? 本地 invokevirtual this.bean.sayHello(...) :
     *           //              ShardRegistry.route(...) 选实例（或 ClusterShardRouter 远端 RPC）
     *           // 3. emitHttpResponseWrite：把 bean 返回值写入 resp
     *       }
     *   }
     * }</pre>
     *
     * <p><b>关键设计：bean 由 Handler 自己从容器查</b>。
     * AppContext 只负责生成 + 实例化，不参与 bean 查找；Handler 构造时按
     * {@code protoIf}（硬编码进字节码）调 {@code BeanContainer.findBeanWrapByType}：
     * <ul>
     *   <li>找到 → 缓存到 {@code this.bean} 字段，handle() 直接 invokevirtual</li>
     *   <li>未找到 → 抛 {@link IllegalStateException}（"No bean for <protoIf>"），
     *       AppContext.generateHandler 包装为 {@link io.edap.container.exc.RouteBindException}
     *       → deploy 失败（fail-fast）</li>
     * </ul>
     * "是否使用 bean / 走本地还是远端 / 是否 shard"等逻辑<b>全在 Handler 自己的字节码里</b>，
     * AppContext 不感知。</p>
     *
     * <p><b>当前状态</b>：占位实现，待 emit 模板逐协议实现后替换。
     * 触发后 AppContext.generateHandler 阶段会抛 RouteBindException（ClassNotFoundException，
     * 因 generateHandlerClass 实际未产出与 className 匹配的字节码）——属于"功能尚未落地"的明确失败，
     * 不静默退化。</p>
     *
     * @param targetIf 协议 typed Handler 接口（HttpHandler / WSServiceMsgHandler / ErpcHandler / GrpcHandler）
     * @param protoIf  proto 服务接口（{@code GreeterService.class}）—— Handler 构造时按此查 bean；
     *                  同时也是字节码里 invokevirtual 的方法签名来源（与 {@code method.getName()} 一起）
     * @param method   bean 上的目标 Method —— 仅取 {@code method.getName()} / 参数类型；
     *                  {@code protoIf.getMethod(method.getName(), ...)} 提供方法签名
     * @param annoDatas 协议注解（{@code @ProtoHttp} / {@code @ProtoWebSocket} / eRPC descriptor / ...）——
     *                  驱动 emit 模板的参数提取分支（path / method / body 等）。
     *                  对同一 Method 同时是 HTTP + WS 的情形，调用方按 annoData 拆成两次 generate。
     * @return 字节码；当前阶段返回空数组占位
     */
    public byte[] generateHandlerClass(Class<?> targetIf, Class<?> protoIf, Method method,
                                       List<AnnoData> annoDatas, ClassLoader loader) {
        // 占位：emit 模板尚未实现；返回空数组。
        // AppContext.generateHandler 阶段调 Class.forName(name, true, genCL) 时会抛 CNFE，
        // 被 RouteBindException 包装（cause = ClassNotFoundException）冒泡到 Container.bindAll，
        // 最终导致 deploy 失败（fail(104)）——这是预期行为，明确告知"功能未落地"。
        if (targetIf == HttpHandler.class) {
            HttpHandlerGenerator generator = new HttpHandlerGenerator(annoDatas, protoIf, method, loader);
            return generator.generate();
        } else if (targetIf == WSServiceMsgHandler.class) {
            WsHandlerGenerator generator = new WsHandlerGenerator(annoDatas, protoIf, method);
            return generator.generate();
        }
        return new byte[0];
    }
}