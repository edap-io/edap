package io.edap.container.test;

import io.edap.Edap;
import io.edap.container.AppContext;
import io.edap.container.Capability;
import io.edap.container.Container;
import io.edap.container.SlotEntry;
import io.edap.grpc.GrpcCall;
import io.edap.grpc.GrpcHandler;
import io.edap.grpc.GrpcMethod;
import io.edap.protobuf.annotation.ProtoGrpc;
import io.edap.util.UnsafeUtil;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 容器层的 gRPC 路由表发布。
 *
 * <p>验的是 {@code Container.rebuildGrpcMapping()} 的三条语义：</p>
 * <ol>
 *   <li>节点启用 {@link Capability#GRPC} 时 attach 阶段建出 {@code GrpcServer} 实例；</li>
 *   <li>路由表<b>只从 CURRENT 槽</b>读 —— STAGING / PREVIOUS 槽的 app 不进表
 *       （STAGING 未上线，PREVIOUS 只承接 in-flight）；</li>
 *   <li>切版本（registry 的 CURRENT 指针换人）后重新发布，表跟着换。</li>
 * </ol>
 *
 * <p><b>为什么用 Unsafe 造 AppContext</b>：AppContext 的构造要一个真实的 EAR 与
 * {@code EdapAppClassLoader}，单元测试里造不出来；而这里要验的只是
 * 「Container 从 CURRENT 槽的 ctx 读方法表」这一件事，与 AppContext 的其余生命周期无关。
 * 手法与 {@link PrevReleaseTimerTest} 一致。</p>
 */
public class GrpcRoutingTest {

    /**
     * 造一个只带 gRPC 方法表的 AppContext。
     */
    private static AppContext ctxWith(String path) throws Exception {
        AppContext ctx = (AppContext) UnsafeUtil.allocateInstance(AppContext.class);
        Map<String, GrpcMethod> methods = new HashMap<>();
        methods.put(path, new GrpcMethod(path, new NoopHandler(),
                ProtoGrpc.MethodType.UNARY, Object.class, Object.class));
        Field f = AppContext.class.getDeclaredField("grpcMethodsByPath");
        f.setAccessible(true);
        f.set(ctx, methods);
        return ctx;
    }

    private static Container newGrpcContainer() {
        Container container = new Container(new File("ignored"), EnumSet.of(Capability.GRPC));
        container.attach(new Edap());
        return container;
    }

    @SuppressWarnings("unchecked")
    private static ConcurrentHashMap<String, SlotEntry> registry(Container container) throws Exception {
        Field f = Container.class.getDeclaredField("registry");
        f.setAccessible(true);
        return (ConcurrentHashMap<String, SlotEntry>) f.get(container);
    }

    private static void rebuild(Container container) throws Exception {
        Method m = Container.class.getDeclaredMethod("rebuildGrpcMapping");
        m.setAccessible(true);
        m.invoke(container);
    }

    @Test
    public void attachCreatesGrpcServerWhenCapabilityEnabled() {
        Container container = newGrpcContainer();
        assertNotNull(container.grpcServer(), "启用 GRPC 能力时必须建出 GrpcServer 实例");
    }

    @Test
    public void grpcServerIsNullWhenCapabilityDisabled() {
        Container container = new Container(new File("ignored"), EnumSet.of(Capability.HTTP));
        container.attach(new Edap());
        assertNull(container.grpcServer(), "没启用 GRPC 能力时不该建 GrpcServer");
    }

    @Test
    public void onlyCurrentSlotIsPublished() throws Exception {
        Container container = newGrpcContainer();
        AppContext current = ctxWith("/demo.Svc/Unary");
        AppContext staging = ctxWith("/demo.Svc/Staged");

        ConcurrentHashMap<String, SlotEntry> reg = registry(container);
        reg.put("app1", new SlotEntry(null, current, staging));   // STAGING 槽不参与
        reg.put("app2", new SlotEntry(null, null, staging));      // 只有 STAGING → 完全不参与

        rebuild(container);

        Map<String, GrpcMethod> published = container.grpcServer().getMethods();
        assertEquals(1, published.size(), "只有 CURRENT 槽的 app 进表，实际: " + published.keySet());
        assertTrue(published.containsKey("/demo.Svc/Unary"));
    }

    @Test
    public void emptyRegistryPublishesEmptyTable() throws Exception {
        Container container = newGrpcContainer();
        rebuild(container);
        assertTrue(container.grpcServer().getMethods().isEmpty(), "没有任何 app 时路由表为空");
    }

    @Test
    public void switchingVersionReplacesPublishedTable() throws Exception {
        Container container = newGrpcContainer();
        AppContext v1 = ctxWith("/demo.Svc/V1");
        AppContext v2 = ctxWith("/demo.Svc/V2");

        ConcurrentHashMap<String, SlotEntry> reg = registry(container);
        reg.put("app1", new SlotEntry(null, v1, null));
        rebuild(container);
        assertEquals(1, container.grpcServer().getMethods().size());
        assertTrue(container.grpcServer().getMethods().containsKey("/demo.Svc/V1"));

        // 切版本：STAGING → CURRENT，老 current 降为 PREVIOUS
        reg.put("app1", new SlotEntry(v1, v2, null));
        rebuild(container);

        Map<String, GrpcMethod> published = container.grpcServer().getMethods();
        assertEquals(1, published.size(), "切版本后表里只应有新版本，实际: " + published.keySet());
        assertTrue(published.containsKey("/demo.Svc/V2"), "切版本后必须发布新版本的方法");
        assertTrue(!published.containsKey("/demo.Svc/V1"), "老版本不该再接流量");
    }

    @Test
    public void undeployRemovesPublishedMethods() throws Exception {
        Container container = newGrpcContainer();
        ConcurrentHashMap<String, SlotEntry> reg = registry(container);
        reg.put("app1", new SlotEntry(null, ctxWith("/demo.Svc/Unary"), null));
        rebuild(container);
        assertEquals(1, container.grpcServer().getMethods().size());

        // 卸掉 current → registry 里该 appId 消失
        reg.remove("app1");
        rebuild(container);
        assertTrue(container.grpcServer().getMethods().isEmpty(), "卸载后路由表必须清空");
    }

    @Test
    public void multiAppMethodsAreMerged() throws Exception {
        Container container = newGrpcContainer();
        ConcurrentHashMap<String, SlotEntry> reg = registry(container);
        reg.put("app1", new SlotEntry(null, ctxWith("/a.Svc/One"), null));
        reg.put("app2", new SlotEntry(null, ctxWith("/b.Svc/Two"), null));

        rebuild(container);

        Map<String, GrpcMethod> published = container.grpcServer().getMethods();
        assertEquals(2, published.size(), "多个 app 的方法要合并进同一张表");
        assertTrue(published.containsKey("/a.Svc/One"));
        assertTrue(published.containsKey("/b.Svc/Two"));
    }

    /**
     * 占位 handler —— 路由表发布不关心 handler 行为。
     */
    private static final class NoopHandler implements GrpcHandler {
        @Override
        public void handle(GrpcCall call) {
        }
    }
}
