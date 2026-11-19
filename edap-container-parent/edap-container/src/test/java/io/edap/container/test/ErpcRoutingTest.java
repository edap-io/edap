/*
 * Copyright 2023 The edap Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at:
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.edap.container.test;

import io.edap.Edap;
import io.edap.container.AppContext;
import io.edap.container.Capability;
import io.edap.container.Container;
import io.edap.container.SlotEntry;
import io.edap.container.app.ErpcMethodDef;
import io.edap.container.exc.RouteBindException;
import io.edap.erpc.ErpcHandler;
import io.edap.erpc.ErpcMethod;
import io.edap.erpc.ErpcMethodIds;
import io.edap.erpc.ErpcRequest;
import io.edap.erpc.ErpcResponse;
import io.edap.util.UnsafeUtil;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 容器层的 eRPC 路由表发布。
 *
 * <p>验的是 {@code Container.rebuildErpcMapping()} 的几条语义：</p>
 * <ol>
 *   <li>节点启用 {@link Capability#ERPC} 时 attach 阶段建出 {@code ErpcServer} 实例；</li>
 *   <li>路由表<b>只从 CURRENT 槽</b>读 —— STAGING / PREVIOUS 槽的 app 不进表；</li>
 *   <li>methodId 按 {@code edap.binding.erpc.methodIdStrategy} 统一分配；</li>
 *   <li>两个方法算到同一个 methodId ⇒ <b>fail-fast</b>（部署期失败，不等运行期串请求）；</li>
 *   <li>{@code @ProtoErpc(methodId = ...)} 显式指定的号优先于自动分配。</li>
 * </ol>
 *
 * <p><b>为什么用 Unsafe 造 AppContext</b>：AppContext 的构造要一个真实的 EAR 与
 * {@code EdapAppClassLoader}，单元测试里造不出来；而这里要验的只是
 * 「Container 从 CURRENT 槽的 ctx 读方法表 + 分配 methodId」这一件事。
 * 手法与 {@link GrpcRoutingTest} 一致。</p>
 */
public class ErpcRoutingTest {

    /**
     * 造一个只带 eRPC 方法表的 AppContext。
     *
     * @param key       方法键（{@code serviceName.methodName}）
     * @param explicitId 显式 methodId；{@code -1} = 交给容器自动分配
     */
    private static AppContext ctxWith(String key, int explicitId) throws Exception {
        AppContext ctx = (AppContext) UnsafeUtil.allocateInstance(AppContext.class);
        Map<String, ErpcMethodDef> methods = new HashMap<>();
        String[] parts = key.split("\\.", 2);
        methods.put(key, new ErpcMethodDef(parts[0], parts.length > 1 ? parts[1] : "",
                new NoopHandler(), Object.class, Object.class, explicitId));
        Field f = AppContext.class.getDeclaredField("erpcMethodsByKey");
        f.setAccessible(true);
        f.set(ctx, methods);
        return ctx;
    }

    private static Container newErpcContainer() {
        Container container = new Container(new File("ignored"), EnumSet.of(Capability.ERPC));
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
        Method m = Container.class.getDeclaredMethod("rebuildErpcMapping");
        m.setAccessible(true);
        try {
            m.invoke(container);
        } catch (InvocationTargetException e) {
            // 把被调方法的原始异常抛出来（否则断言只能看到 InvocationTargetException）
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException) {
                throw (RuntimeException) cause;
            }
            throw e;
        }
    }

    @Test
    public void attachCreatesErpcServerWhenCapabilityEnabled() {
        Container container = newErpcContainer();
        assertNotNull(container.erpcServer(), "启用 ERPC 能力时必须建出 ErpcServer 实例");
    }

    @Test
    public void erpcServerIsNullWhenCapabilityDisabled() {
        Container container = new Container(new File("ignored"), EnumSet.of(Capability.HTTP));
        container.attach(new Edap());
        assertNull(container.erpcServer(), "没启用 ERPC 能力时不该建 ErpcServer");
    }

    @Test
    public void onlyCurrentSlotIsPublished() throws Exception {
        Container container = newErpcContainer();
        AppContext current = ctxWith("demo.Svc.Unary", -1);
        AppContext staging = ctxWith("demo.Svc.Staged", -1);

        ConcurrentHashMap<String, SlotEntry> reg = registry(container);
        reg.put("app1", new SlotEntry(null, current, staging));   // STAGING 槽不参与
        reg.put("app2", new SlotEntry(null, null, staging));      // 只有 STAGING → 完全不参与

        rebuild(container);

        Map<Integer, ErpcMethod> published = container.erpcServer().getMethods();
        assertEquals(1, published.size(), "只有 CURRENT 槽的 app 进表，实际: " + published.keySet());
    }

    @Test
    public void emptyRegistryPublishesEmptyTable() throws Exception {
        Container container = newErpcContainer();
        rebuild(container);
        assertTrue(container.erpcServer().getMethods().isEmpty(), "没有任何 app 时路由表为空");
    }

    @Test
    public void hashStrategyAssignsMethodIdFromMethodKey() throws Exception {
        Container container = newErpcContainer();
        ConcurrentHashMap<String, SlotEntry> reg = registry(container);
        reg.put("app1", new SlotEntry(null, ctxWith("demo.Svc.Unary", -1), null));

        rebuild(container);

        Map<Integer, ErpcMethod> published = container.erpcServer().getMethods();
        assertEquals(1, published.size());
        int expected = ErpcMethodIds.hashKey("demo.Svc.Unary", ErpcMethodIds.DEFAULT_SEED);
        assertTrue(published.containsKey(expected),
                "methodId 必须由方法键哈希得到，期望 " + expected + "，实际 " + published.keySet());
    }

    @Test
    public void explicitMethodIdWins() throws Exception {
        Container container = newErpcContainer();
        ConcurrentHashMap<String, SlotEntry> reg = registry(container);
        reg.put("app1", new SlotEntry(null, ctxWith("demo.Svc.Unary", 10001), null));

        rebuild(container);

        Map<Integer, ErpcMethod> published = container.erpcServer().getMethods();
        assertEquals(1, published.size());
        assertTrue(published.containsKey(10001),
                "显式指定的 methodId 必须生效，实际 " + published.keySet());
    }

    @Test
    public void multiAppMethodsAreMerged() throws Exception {
        Container container = newErpcContainer();
        ConcurrentHashMap<String, SlotEntry> reg = registry(container);
        reg.put("app1", new SlotEntry(null, ctxWith("a.Svc.One", -1), null));
        reg.put("app2", new SlotEntry(null, ctxWith("b.Svc.Two", -1), null));

        rebuild(container);

        assertEquals(2, container.erpcServer().getMethods().size(), "多个 app 的方法要合并进同一张表");
    }

    /**
     * ★ 两个方法撞到同一个 methodId ⇒ fail-fast（不等运行期发现请求串了）。
     */
    @Test
    public void conflictingMethodIdFailsFast() throws Exception {
        Container container = newErpcContainer();
        ConcurrentHashMap<String, SlotEntry> reg = registry(container);
        // 两个不同方法显式指定同一个号
        reg.put("app1", new SlotEntry(null, ctxWith("a.Svc.One", 10001), null));
        reg.put("app2", new SlotEntry(null, ctxWith("b.Svc.Two", 10001), null));

        RouteBindException e = assertThrows(RouteBindException.class, () -> rebuild(container));
        assertTrue(e.getMessage().contains("methodId 冲突"), "异常信息应说明是 methodId 冲突: " + e.getMessage());
    }

    /**
     * 占位 handler —— 路由表发布不关心 handler 行为。
     */
    private static final class NoopHandler implements ErpcHandler {
        @Override
        public void handle(ErpcRequest req, ErpcResponse resp) {
        }
    }
}
