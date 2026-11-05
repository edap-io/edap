/*
 * Copyright (c) 2019 louis.lu
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */

package io.edap.http.server.test.decoder;

import io.edap.buffer.FastBuf;
import io.edap.http.HttpRequest;
import io.edap.http.PathInfo;
import io.edap.http.ValueHttpRequest;
import io.edap.http.codec.HttpFastBufDataRange;
import io.edap.http.server.HttpServer;
import io.edap.http.server.HttpServerBuilder;
import io.edap.http.server.rangedecoder.PathDecoder;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * URL 编码路径命中精确 cache 的测试。
 *
 * <p><b>守的 bug</b>：请求侧（{@link PathDecoder#decode}）对<b>解码后</b>的字节算 FNV hash，
 * 注册侧若只用原始路径字节算 hash，两者永远对不上 ⇒ 注册 {@code /a%20b} 时精确 cache
 * 永远 miss，只能退回通配符路由。</p>
 *
 * <p>两段验证：</p>
 * <ol>
 *   <li><b>交叉校验</b>：{@code HttpFastBufDataRange.decodePathBytes} 的输出必须与
 *       {@code PathDecoder} 算出的 length / hash / first / last 完全一致；</li>
 *   <li><b>端到端</b>：注册 {@code /a%20b} 后，请求 {@code /a%20b} 必须命中精确 cache
 *       （{@code PathInfo.getFound()} 为 true）。</li>
 * </ol>
 */
public class UrlEncodedPathCacheTest {

    /**
     * 覆盖：纯 ASCII、%XX、大写 hex、小写 hex（非法）、'+'、多段编码、非 ASCII UTF-8。
     */
    private static final String[] PATHS = {
            "/plain",
            "/a%20b",
            "/a+b",
            "/a%2Fb",
            "/%41",
            "/%E4%B8%AD%E6%96%87",
            "/dir/file%20name.txt",
            "/a%20b%20c",
            "/%2B",
    };

    /**
     * 交叉校验：decodePathBytes 的字节序列 ⇒ 构造出的 dataRange 必须与 PathDecoder
     * 在真实请求上算出的 dataRange 完全等价（length / hash / first / last / getString）。
     */
    @Test
    public void testDecodePathBytesMatchesPathDecoder() {
        for (String path : PATHS) {
            HttpFastBufDataRange requestRange = decodeRequest(path);

            byte[] decoded = HttpFastBufDataRange.decodePathBytes(path);
            assertNotNull(decoded, "路径 " + path + " 应当可以解码");
            HttpFastBufDataRange alias = HttpFastBufDataRange.fromBytes(decoded);

            assertEquals(requestRange.length(), alias.length(), "长度不一致: " + path);
            assertEquals(requestRange.hash(), alias.hash(), "hash 不一致: " + path);
            assertEquals(requestRange.first(), alias.first(), "首字节不一致: " + path);
            assertEquals(requestRange.last(), alias.last(), "尾字节不一致: " + path);
            assertEquals(requestRange.getString(), alias.getString(), "路径字符串不一致: " + path);
            // equals 是精确 cache 的命中判据（equalsLoose）
            assertTrue(alias.equals(requestRange), "别名 key 与请求侧 dataRange 不相等: " + path);
            assertTrue(requestRange.equals(alias), "别名 key 与请求侧 dataRange 不相等: " + path);
        }
    }

    /**
     * 端到端：注册 URL 编码路径后，请求同样的编码路径必须命中精确 cache。
     */
    @Test
    public void testEncodedPathHitsExactCache() {
        HttpServer server = new HttpServerBuilder()
                .get("/a%20b", (req, resp) -> resp.write("hit"))
                .get("/%E4%B8%AD%E6%96%87", (req, resp) -> resp.write("hit"))
                .build();

        for (String path : new String[]{"/a%20b", "/%E4%B8%AD%E6%96%87"}) {
            HttpFastBufDataRange requestRange = decodeRequest(path);
            PathInfo pathInfo = server.lookup(requestRange);
            assertNotNull(pathInfo, "路径 " + path + " 没有查到 PathInfo");
            assertTrue(pathInfo.isFound(), "路径 " + path + " 没有命中精确 cache");
            assertEquals(path, pathInfo.getMatchPath(), "命中了错误的 PathInfo: " + path);
        }
    }

    /**
     * 回归：未编码的路径仍然命中（双 key 注册不能破坏原有行为）。
     */
    @Test
    public void testPlainPathStillHitsExactCache() {
        HttpServer server = new HttpServerBuilder()
                .get("/plain", (req, resp) -> resp.write("hit"))
                .build();
        PathInfo pathInfo = server.lookup(decodeRequest("/plain"));
        assertNotNull(pathInfo);
        assertTrue(pathInfo.isFound());
    }

    /**
     * 未注册的路径查不到（不能因为别名注册而误命中）。
     */
    @Test
    public void testUnregisteredPathNotFound() {
        HttpServer server = new HttpServerBuilder()
                .get("/a%20b", (req, resp) -> resp.write("hit"))
                .build();
        PathInfo pathInfo = server.lookup(decodeRequest("/a%20c"));
        assertNotNull(pathInfo);
        assertTrue(!pathInfo.isFound(), "未注册的路径不应命中");
    }

    /**
     * 非法 escape（小写 hex）⇒ 不生成别名，也不改变原有行为（请求时由 PathDecoder 报错）。
     */
    @Test
    public void testIllegalEscapeSkipped() {
        // 小写 hex 不在 BYTE_VALUES 覆盖范围内 ⇒ decodePathBytes 返回 null
        assertNull(HttpFastBufDataRange.decodePathBytes("/a%2fb"));
        // escape 不完整
        assertNull(HttpFastBufDataRange.decodePathBytes("/a%2"));
        assertNull(HttpFastBufDataRange.decodePathBytes("/a%"));
    }

    /**
     * 用 PathDecoder 解析一个「路径 + 空格」的请求，取出请求侧的 dataRange。
     */
    private static HttpFastBufDataRange decodeRequest(String path) {
        PathDecoder pathDecoder = new PathDecoder();
        FastBuf buf = new FastBuf(1024);
        HttpFastBufDataRange range = new HttpFastBufDataRange();
        HttpRequest request = new ValueHttpRequest();
        byte[] raw = path.getBytes(StandardCharsets.UTF_8);
        buf.write(raw, 0, raw.length);
        buf.write((byte) ' ');
        pathDecoder.decode(buf, range, request);
        return range;
    }
}
