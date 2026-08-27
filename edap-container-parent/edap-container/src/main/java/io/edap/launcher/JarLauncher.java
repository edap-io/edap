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
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

import static io.edap.launcher.EdapContainerClassLoader.scanLibJars;

public class JarLauncher {

    private static File BOOT_JAR_FILE;

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
        File jarFile = locateBootJarFile();
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
        List<NestedJarFile> libJars = scanLibJars(root, "BOOT-INF/");
        log("[JarLauncher] Lib jars: " + libJars.size());
        EdapContainerClassLoader cl = new EdapContainerClassLoader(root, libJars,
                "BOOT-INF/classes/", jarFile.getAbsolutePath(), parent);
        log("[JarLauncher] LaunchedURLClassLoader constructed (parent=" + parent.getClass().getName() + ")");

        // ★ 全局 TCCL —— Spring Boot JarLauncher.launch() 的标准做法。
        // 反射调用 Start-Class.main 之前把当前线程 TCCL 设为 appCL,后续:
        //   - DriverManager.ensureDriversInitialized() → ServiceLoader.load(Driver.class)
        //     用 TCCL 找 META-INF/services/java.sql.Driver,只有 TCCL = appCL 才能
        //     透过 EdapContainerClassLoader.findResources 拿到嵌套 jar 里的 SPI 文件
        //   - HikariCP pool 线程继承 TCCL = appCL,isDriverAllowed(driver, callerCL=TCCL)
        //     能 Class.forName 到 driver 类,driver 通过校验
        //   - 业务代码 ServiceLoader.load(Foo.class) 同理直接生效
        Thread.currentThread().setContextClassLoader(cl);
        log("[JarLauncher] ✓ set TCCL = " + cl.getClass().getSimpleName());

        // 5. 反射调用 Start-Class.main
        Class<?> appClass = Class.forName(startClass, false, cl);
        log("[JarLauncher] Resolved Start-Class: " + appClass.getName());
        log("[JarLauncher]   loaded by: " + appClass.getClassLoader().getClass().getName());

        Method m = appClass.getMethod("main", String[].class);
        m.invoke(null, (Object) args);

        // JarLauncher.invoke main 后
        ClassLoader appCl = appClass.getClassLoader();
        if (!(appCl instanceof EdapContainerClassLoader)) {  // 或 EdapAppURLClassLoader
            throw new IllegalStateException(
                    "Start-Class loaded by " + appCl.getClass().getName() +
                            " instead of the nested classloader. " +
                            "Check that business classes are in BOOT-INF/classes/, not jar root.");
        }
    }

    /** 通过 ProtectionDomain → CodeSource 拿 jar 文件路径。 */
    public static File locateBootJarFile() {
        if (BOOT_JAR_FILE == null) {
            try {
                URL loc = JarLauncher.class.getProtectionDomain().getCodeSource().getLocation();
                File f = new File(loc.toURI());
                if (!f.isFile()) {
                    throw new IllegalStateException("Launcher is not inside a file jar: " + loc);
                }
                BOOT_JAR_FILE = f;

                return BOOT_JAR_FILE;
            } catch (Exception e) {
                throw new IllegalStateException("Cannot locate outer jar file", e);
            }
        }

        return BOOT_JAR_FILE;
    }

    private static void log(String msg) {
        System.out.println(msg);
    }
}
