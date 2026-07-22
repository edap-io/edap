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

package io.edap.launcher;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

public class JarLauncher {

    private static final String LIB_PREFIX = "BOOT-INF/lib/";
    private static final String CLASSES_PREFIX = "BOOT-INF/classes/";

    public static void main(String[] args) throws IOException, ClassNotFoundException, NoSuchMethodException, InvocationTargetException, IllegalAccessException {
        System.out.println("container jarLaucher start...");
        // ★ 设置 java.protocol.handler.pkgs,必须在第一次 new URL("nested:...") 之前
        // 告诉 JDK:nested 协议的 Handler 在 io.edap.launcher.nested.Handler
        String current = System.getProperty("java.protocol.handler.pkgs", "");
        if (!current.contains("io.edap.launcher")) {
            String next = current.isEmpty() ? "io.edap.launcher" : current + "|io.edap.launcher";
            System.setProperty("java.protocol.handler.pkgs", next);
            System.out.println("[JarLauncher] ✓ registered nested: via java.protocol.handler.pkgs");
        }

        // 1. 定位 jar
        File jarFile = locateJarFile();
        log("[JarLauncher] Outer jar: " + jarFile.getAbsolutePath() + " (size=" + jarFile.length() + ")");

        // 2. 读 MANIFEST
        Manifest manifest;
        try (JarFile jf = new JarFile(jarFile)) {
            manifest = jf.getManifest();
        }
        if (manifest == null) {
            throw new IllegalStateException("Missing META-INF/MANIFEST.MF in " + jarFile);
        }

        Attributes attrs = manifest.getMainAttributes();
        String startClass = attrs.getValue("Start-Class");
        if (startClass == null || startClass.isEmpty()) {
            throw new IllegalStateException("MANIFEST missing Start-Class");
        }
        log("[JarLauncher] Start-Class: " + startClass);

        // 3. 用 NestedJarFile 打开外层 jar
        NestedJarFile root;
        try {
            root = new NestedJarFile(jarFile);
            log("[JarLauncher] NestedJarFile opened, entries=" + root.entryNames().size());
        } catch (IOException e) {
            throw new IOException("Failed to open outer jar via NestedJarFile", e);
        }

        // 4. 构造 ClassLoader(直接在 findClass 里读 NestedJarFile,不走 URL)
        ClassLoader parent = JarLauncher.class.getClassLoader();
        // 4. ★ 扫描 BOOT-INF/lib/*.jar,为每个嵌套 jar 构造 NestedJarFile
        //    注意:每个嵌套 jar 共享同一个 RandomAccessFile,只是限定了数据范围。
        List<NestedJarFile> libJars = scanLibJars(root);
        log("[JarLauncher] Lib jars: " + libJars.size());
        EdapContainerURLClassLoader cl = new EdapContainerURLClassLoader(root, libJars, CLASSES_PREFIX, jarFile.getAbsolutePath(),
                parent);
        log("[JarLauncher] LaunchedURLClassLoader constructed (parent=" + parent.getClass().getName() + ")");

        // 5. 反射调用 Start-Class.main
        Class<?> appClass = Class.forName(startClass, false, cl);
        log("[JarLauncher] Resolved Start-Class: " + appClass.getName());
        log("[JarLauncher]   loaded by: " + appClass.getClassLoader().getClass().getName());

        Method m = appClass.getMethod("main", String[].class);
        m.invoke(null, (Object) args);

        // JarLauncher.invoke main 后
        ClassLoader appCl = appClass.getClassLoader();
        if (!(appCl instanceof EdapContainerURLClassLoader)) {  // 或 EdapAppURLClassLoader
            throw new IllegalStateException(
                    "Start-Class loaded by " + appCl.getClass().getName() +
                            " instead of the nested classloader. " +
                            "Check that business classes are in BOOT-INF/classes/, not jar root.");
        }
    }

    /** 通过 ProtectionDomain → CodeSource 拿 jar 文件路径。 */
    private static File locateJarFile() {
        try {
            URL loc = JarLauncher.class.getProtectionDomain().getCodeSource().getLocation();
            File f = new File(loc.toURI());
            if (!f.isFile()) {
                throw new IllegalStateException("Launcher is not inside a file jar: " + loc);
            }
            return f;
        } catch (Exception e) {
            throw new IllegalStateException("Cannot locate outer jar file", e);
        }
    }

    private static void log(String msg) {
        System.out.println(msg);
    }

    /**
     * 扫描外层 jar 的所有 entry,挑出 BOOT-INF/lib/*.jar,
     * 为每个嵌套 jar 构造 NestedJarFile。
     *
     * 顺序:按 entry 名字典序,与 maven-assembly-plugin dependencySet 的输出顺序一致
     * (即 Maven 解析依赖图的顺序,通常 dependencies 在前,transitives 在后)。
     */
    static List<NestedJarFile> scanLibJars(NestedJarFile root) {
        // 用 TreeSet 保证稳定的扫描顺序,便于日志阅读
        Set<String> sortedEntries = new TreeSet<>(root.entryNames());

        List<NestedJarFile> libs = new ArrayList<>();
        for (String entryName : sortedEntries) {
            if (!entryName.startsWith(LIB_PREFIX) || !entryName.endsWith(".jar")) {
                continue;
            }
            try {
                NestedJarFile nested = root.getNestedJarFile(entryName);
                if (nested != null) {
                    libs.add(nested);
                    log("[JarLauncher]   lib: " + entryName + " (entries=" + nested.entryNames().size() + ")");
                }
            } catch (IOException e) {
                log("[JarLauncher]   WARN: failed to open nested jar " + entryName + ": " + e);
            }
        }
        return libs;
    }
}
