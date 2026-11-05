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

package io.edap.crypto.test;

import io.edap.crypto.CryptoRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * edap-crypto <b>不得依赖 edap-log-api</b> —— 静态不变量守卫。
 *
 * <p>edap-crypto 位于<b>日志 bootstrap 路径</b>上（{@code LoggerManager.init()}
 * → SPI provider → appender 初始化 → {@code EncoderGenerator} →
 * {@code CryptUtil.md5} → {@code CryptoRegistry}）。如果 edap-crypto 反过来
 * 依赖 edap-log-api 并在类初始化时取 logger，就会形成跨 4 个模块的
 * <b>类初始化环</b>：</p>
 *
 * <pre>
 * CryptoRegistry.&lt;clinit&gt; --getLogger--&gt; LoggerManager.init() --SPI--&gt;
 *   EdapLogServiceProvider --EncoderGenerator--&gt; CryptUtil.md5--&gt;
 *   CryptoRegistry.digest() ⟲  （同线程重入 ⇒ 静态 map 为 null ⇒ NPE）
 * </pre>
 *
 * <p>后果是 SPI provider 实例化失败 ⇒ <b>整个应用退化成 NopLogger（一行日志都没有）</b>。
 * 事故记录见 {@code io.edap.crypto.internal.CryptoDiagnostics} 的类注释。</p>
 *
 * <p>本测试扫描 edap-crypto 编译产物里的常量池，只要出现 {@code io/edap/log}
 * 就判定失败 —— 这是「edap-crypto 不依赖 edap-log-api」这条约束的机器可验证形式。</p>
 */
public class NoLogApiDependencyTest {

    /**
     * 类文件常量池里的类名是以 {@code /} 分隔的内部名。
     */
    private static final String EDAP_LOG_INTERNAL_NAME = "io/edap/log";

    @Test
    @DisplayName("edap-crypto 的任何类都不得引用 io.edap.log")
    public void testNoReferenceToEdapLog() throws Exception {
        File root = codeSourceDir();
        List<File> classFiles = new ArrayList<>();
        collectClassFiles(root, classFiles);
        assertFalse(classFiles.isEmpty(), "没找到 edap-crypto 的编译产物：" + root);

        List<String> offenders = new ArrayList<>();
        for (File f : classFiles) {
            byte[] bytes = Files.readAllBytes(f.toPath());
            if (containsAscii(bytes, EDAP_LOG_INTERNAL_NAME)) {
                offenders.add(root.toPath().relativize(f.toPath()).toString());
            }
        }

        assertTrue(offenders.isEmpty(),
                "edap-crypto 里出现了对 edap-log 的引用（会形成类初始化环 ⇒ 全应用退化成 NopLogger）："
                        + offenders);
    }

    // ------------------------------------------------------------------ 辅助

    private static File codeSourceDir() {
        URL location = CryptoRegistry.class.getProtectionDomain().getCodeSource().getLocation();
        assertNotNull(location, "拿不到 CryptoRegistry 的 code source");
        try {
            return new File(location.toURI());
        } catch (Exception e) {
            throw new IllegalStateException("解析 code source 失败：" + location, e);
        }
    }

    private static void collectClassFiles(File dir, List<File> out) throws IOException {
        File[] children = dir.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            if (child.isDirectory()) {
                collectClassFiles(child, out);
            } else if (child.getName().endsWith(".class")) {
                out.add(child);
            }
        }
        Collections.sort(out);
    }

    /**
     * 在字节序列里找 ASCII 子串。
     */
    private static boolean containsAscii(byte[] haystack, String needle) {
        byte[] pattern = needle.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        if (pattern.length == 0) {
            return false;
        }
        outer:
        for (int i = 0; i <= haystack.length - pattern.length; i++) {
            for (int j = 0; j < pattern.length; j++) {
                if (haystack[i + j] != pattern[j]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }
}
