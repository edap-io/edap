/*
 * Copyright 2023 The edap Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package io.edap.http.server.test;

import io.edap.http.HttpHandler;
import io.edap.http.PathInfo;
import io.edap.http.codec.HttpFastBufDataRange;
import io.edap.http.server.PathInfoMatcher;
import io.edap.http.server.handler.NotFoundHandler;
import io.edap.nio.codec.FastBufDataRange;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 模拟 Container.rebuildHttpMapping() 的语义:每次 rebuild 新建 PathInfoMatcher,
 * 只把当前(current)槽位的 pathTable 与 wildcard entry register 进去。
 *
 * <p>回归 Bug:Container 旧实现遍历 appPathTables.values() 把 staging / previous /
 * current 三个槽位的 wildcard 全 register 进同一个 matcher,导致 switchVersion 后
 * 陈旧版本仍命中。同 path 不同实例的 PathInfo 因 equals 是引用比较,去重也失效,
 * matcher 里残留多份同名 entry,路由顺序不稳定。</p>
 *
 * <p>修复后(仅 register current + wildcard router 按 path 去重),该测试覆盖三个场景:
 * <ol>
 *   <li>rebuild 后 matcher 只命中 current 版本</li>
 *   <li>跨多个 appId 同时 current,精确路径按 method 合并不丢失</li>
 *   <li>同 path 多次 register(模拟 rebuild 期间重复)只保留一份</li>
 * </ol>
 * </p>
 */
public class PathInfoMatcherRebuildTest {

    private static HttpHandler stubHandler(String tag) {
        return new TaggedHandler(tag);
    }

    @Test
    public void wildcardOnlyCurrentSlotIsRegistered() {
        PathInfoMatcher matcher = new PathInfoMatcher();

        // V2 在 current:V2 的 /* 通配符路由
        PathInfo v2pi = new PathInfo();
        v2pi.setPath("/*");
        v2pi.setHttpHandlers(new HttpHandler[]{stubHandler("v2")});
        matcher.registerPostfixMatcher(v2pi);

        // V1 (从 current 切到 previous):即使 pathTable 也想 register,rebuild 路径不再读它
        // 这里不调 matcher.registerPostfixMatcher,模拟修复后 rebuild 的过滤行为

        PathInfo hit = matcher.match("/anything");
        assertNotNull(hit);
        assertTrue(hit.isFound(), "rebuild 后 wildcard 路由应命中");
        assertEquals(1, hit.getHttpHandlers().length);
        assertEquals("v2", ((TaggedHandler) hit.getHttpHandlers()[0]).tag);
    }

    @Test
    public void wildcardLastRegisterWinsWhenMultipleDistinctPaths() {
        PathInfoMatcher matcher = new PathInfoMatcher();

        PathInfo shortPi = new PathInfo();
        shortPi.setPath("/*");
        shortPi.setHttpHandlers(new HttpHandler[]{stubHandler("short")});
        matcher.registerPostfixMatcher(shortPi);

        PathInfo longPi = new PathInfo();
        longPi.setPath("/api/*");
        longPi.setHttpHandlers(new HttpHandler[]{stubHandler("long")});
        matcher.registerPostfixMatcher(longPi);

        // /api/users 应匹配更长的 /api/*
        PathInfo hit = matcher.match("/api/users");
        assertNotNull(hit);
        assertEquals("long", ((TaggedHandler) hit.getHttpHandlers()[0]).tag);

        // /users 匹配 /*
        hit = matcher.match("/users");
        assertNotNull(hit);
        assertEquals("short", ((TaggedHandler) hit.getHttpHandlers()[0]).tag);
    }

    @Test
    public void exactPathCacheReplacesOnRebuild() {
        // 第一次 rebuild:仅 V1
        PathInfoMatcher matcherV1 = new PathInfoMatcher();
        Map<FastBufDataRange, PathInfo> v1Cache = new HashMap<>();
        PathInfo v1Pi = new PathInfo();
        v1Pi.setPath("/api/list");
        v1Pi.setHttpHandlers(new HttpHandler[]{stubHandler("v1")});
        v1Cache.put(FastBufDataRange.from("/api/list"), v1Pi);
        matcherV1.setCache(v1Cache);

        // 第二次 rebuild:仅 V2(matcher 实例是新的,模拟 rebuildHttpMapping 每次 new)
        PathInfoMatcher matcherV2 = new PathInfoMatcher();
        Map<FastBufDataRange, PathInfo> v2Cache = new HashMap<>();
        PathInfo v2Pi = new PathInfo();
        v2Pi.setPath("/api/list");
        v2Pi.setHttpHandlers(new HttpHandler[]{stubHandler("v2")});
        v2Cache.put(FastBufDataRange.from("/api/list"), v2Pi);
        matcherV2.setCache(v2Cache);

        PathInfo hit = matcherV2.match(HttpFastBufDataRange.from("/api/list"));
        assertEquals("v2", ((TaggedHandler) hit.getHttpHandlers()[0]).tag);

        // V1 matcher 仍能命中(模拟老 reader 看到的旧 matcher,符合 in-flight 语义)
        hit = matcherV1.match(HttpFastBufDataRange.from("/api/list"));
        assertEquals("v1", ((TaggedHandler) hit.getHttpHandlers()[0]).tag);
    }

    /**
     * 模拟 rebuild 期间被多次调用 register(对应 Bug 2 修复前的根源):
     * 修复前 equals 引用比较失效,prefix 数组里残留多份;修复后只保留 1 份。
     */
    @Test
    public void wildcardRouterDedupeAcrossInstances() {
        PathInfoMatcher matcher = new PathInfoMatcher();

        // 第一次 register("v1")
        PathInfo first = new PathInfo();
        first.setPath("/*");
        first.setHttpHandlers(new HttpHandler[]{stubHandler("v1")});
        matcher.registerPostfixMatcher(first);

        // 第二次 register,不同实例同 path("v2")—— 模拟"两个 AppContext 都注册了 /*"
        PathInfo second = new PathInfo();
        second.setPath("/*");
        second.setHttpHandlers(new HttpHandler[]{stubHandler("v2")});
        matcher.registerPostfixMatcher(second);

        PathInfo hit = matcher.match("/anywhere");
        assertNotNull(hit);
        assertEquals("v1", ((TaggedHandler) hit.getHttpHandlers()[0]).tag,
                "按 path 字符串去重后应保留首次注册实例(first)");
    }

    static final class TaggedHandler implements HttpHandler {
        final String tag;

        TaggedHandler(String tag) {
            this.tag = tag;
        }

        @Override
        public void handle(io.edap.http.HttpRequest req, io.edap.http.HttpResponse resp) {
            // no-op
        }
    }
}
