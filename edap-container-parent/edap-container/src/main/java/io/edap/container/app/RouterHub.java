package io.edap.container.app;

import io.edap.container.ws.WSServiceMsgHandler;
import io.edap.grpc.GrpcHandler;
import io.edap.http.HttpHandler;
import io.edap.rpc.ErpcHandler;

import java.util.ArrayList;
import java.util.List;

/**
 * RouterHub = 4 份协议 typed Handler List 的被动持有者。
 *
 * 元素类型 = 各协议 typed Handler 接口：
 *   - HttpHandler             （io.edap.http）
 *   - WSServiceMsgHandler<?>  （io.edap.container.ws —— 容器内 functional interface）
 *   - ErpcHandler             （io.edap.erpc）
 *   - GrpcHandler             （io.edap.grpc）
 * 实现类由 {@code AppContext.generateHandler(targetIf, annoData, shard, bean, method)} 用 ASM 字节码生成
 * （见 §3.5.7），协议入口方法（handle / handle(msg)）字节码按 bean method 参数类型
 * 硬编码 cast + 直接 invokevirtual——热路径零反射。
 *
 * Shard 不再独立成 5 份 List 之一：分片亲和由方法级 {@code @Sharded} 注解承载（位于
 * {@code pmd.annoDatas}，调用方 {@code AppContext.generateAndBindRoutes} 在按 anno.type 分派
 * 到协议 Handler 时顺便抽取 shard boolean）；当 {@code shard == true} 时，生成 Handler 内部
 * 用 ShardRegistry 按 shardKey 选实例，所有协议统一走 handle(req, resp) / handle(msg)
 * 入口，零特殊协议类型。
 *
 * 注意：io.edap.http.WSHandler 是 WS 连接级事件接口，不在 4 份路由表里——它由 ServiceWSHandler 处理。
 *
 * RouterHub 不做 ProtoServiceData → Handler 的解析、不感知 ClassLoader、不做反射。
 */
public class RouterHub {

    private final List<HttpHandler>            httpHandlers  = new ArrayList<>();
    private final List<WSServiceMsgHandler<?>> wsHandlers    = new ArrayList<>();
    private final List<ErpcHandler>            erpcHandlers  = new ArrayList<>();
    private final List<GrpcHandler>            grpcHandlers  = new ArrayList<>();

    /** setHandlers / unbindAll 状态栅栏；同时给 unbindAll 当幂等门。 */
    private volatile boolean bound;

    /**
     * 一次性写入 4 份 Handler List（调用方：Container.bindAll，见 §3.5.6）。
     *
     * 原子：bound==true 时拒绝再次写入；任一参数 null 整体抛异常，4 个字段保持不变。
     * 元素是 AppContext.generateHandler 阶段 ASM 生成、对应协议 typed Handler 接口的实现类。
     */
    public void setHandlers(List<HttpHandler>            httpHandlers,
                            List<WSServiceMsgHandler<?>> wsHandlers,
                            List<ErpcHandler>            erpcHandlers,
                            List<GrpcHandler>            grpcHandlers) {
        if (bound) {
            throw new IllegalStateException("RouterHub already bound");
        }
        if (httpHandlers == null || wsHandlers == null || erpcHandlers == null
                || grpcHandlers == null) {
            throw new IllegalArgumentException("setHandlers: 4 份 List 任一为 null");
        }
        this.httpHandlers.addAll(httpHandlers);
        this.wsHandlers.addAll(wsHandlers);
        this.erpcHandlers.addAll(erpcHandlers);
        this.grpcHandlers.addAll(grpcHandlers);
        this.bound = true;
    }

    /**
     * 清空 4 份 Handler List。调用方：AppContext.stop()。
     *
     * 用 clear() 而非 = new ArrayList<>()：4 个 List 字段是 final，
     * 保留 List 实例的引用，外部如果在 unbindAll 期间还在 dispatch（in-flight 请求），
     * 不会突然看到 List 引用换成 null 抛 NPE。
     * 幂等：未 bound 或重复调则 no-op。
     */
    public void unbindAll() {
        if (!bound) return;
        httpHandlers.clear();
        wsHandlers.clear();
        erpcHandlers.clear();
        grpcHandlers.clear();
        bound = false;
    }

    public List<HttpHandler>            httpHandlers()  { return httpHandlers;  }
    public List<WSServiceMsgHandler<?>> wsHandlers()    { return wsHandlers;    }
    public List<ErpcHandler>            erpcHandlers()  { return erpcHandlers;  }
    public List<GrpcHandler>            grpcHandlers()  { return grpcHandlers;  }

    public boolean isBound() { return bound; }
}
