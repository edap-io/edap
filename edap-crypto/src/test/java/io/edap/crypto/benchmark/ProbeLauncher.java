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

package io.edap.crypto.benchmark;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

/**
 * 起子 JVM 跑探针的公共设施。
 *
 * <p><b>为什么要拆出来</b>：微基准的一条铁律是「一个候选一个 JVM」——
 * 同一个 JVM 里连测两个候选，调用点会变成 megamorphic，JIT 按先跑者的形态编译后跑者，
 * 读出来的是「谁排在后面谁吃亏」而不是真实差距（见 memory
 * {@code feedback_benchmark_isolated_jvm}）。于是每个探针都得能被别人用子进程拉起来，
 * 而「怎么把完整 classpath 传给子进程」这件事在 surefire 下有个坑
 * （{@link #classpath()}），抄一遍就会抄错 —— 所以只留这一份。</p>
 */
public final class ProbeLauncher {

    /**
     * 子进程的 classpath（懒加载 + 缓存）。
     */
    private static String childClasspath;

    private ProbeLauncher() {
        // 工具类
    }

    /**
     * 还原「完整 classpath」。
     *
     * <p><b>为什么要还原</b>：surefire 默认 {@code useManifestOnlyJar=true}，
     * 被测 JVM 的 {@code java.class.path} 只有一个 booter jar，
     * 真正的依赖都写在该 jar manifest 的 {@code Class-Path} 里 ——
     * 直接拿 {@code java.class.path} 去起子进程会「什么类都找不到」。</p>
     */
    public static synchronized String classpath() {
        if (childClasspath != null) {
            return childClasspath;
        }
        List<String> entries = new ArrayList<>();
        for (String entry : System.getProperty("java.class.path", "").split(File.pathSeparator)) {
            if (entry.isEmpty()) {
                continue;
            }
            entries.add(entry);
            expandManifest(entry, entries);
        }
        childClasspath = String.join(File.pathSeparator, entries);
        return childClasspath;
    }

    /**
     * 把 jar manifest 里的 {@code Class-Path} 展开进列表（相对路径按 jar 所在目录解析）。
     */
    private static void expandManifest(String path, List<String> out) {
        if (!path.endsWith(".jar")) {
            return;
        }
        try (JarFile jar = new JarFile(path)) {
            Manifest manifest = jar.getManifest();
            if (manifest == null) {
                return;
            }
            String value = manifest.getMainAttributes().getValue("Class-Path");
            if (value == null) {
                return;
            }
            File base = new File(path).getAbsoluteFile().getParentFile();
            for (String item : value.trim().split("\\s+")) {
                if (item.isEmpty()) {
                    continue;
                }
                String absolute = new File(base, item).getAbsolutePath();
                if (!out.contains(absolute)) {
                    out.add(absolute);
                    expandManifest(absolute, out);
                }
            }
        } catch (Exception e) {
            // 不是 jar / 读不到 manifest —— 忽略，classpath 上少一项总比整个失败好
        }
    }

    /**
     * 当前 JVM 的 {@code java} 可执行文件路径。
     */
    public static String javaBin() {
        String home = System.getProperty("java.home");
        if (home != null) {
            Path bin = Paths.get(home, "bin", "java");
            if (Files.isExecutable(bin)) {
                return bin.toString();
            }
        }
        return "java";
    }

    /**
     * 起一个子 JVM 跑指定主类，返回它合并后的 stdout/stderr。
     *
     * @param mainClass 主类全名
     * @param args      主类参数
     */
    public static String run(String mainClass, List<String> args) throws IOException, InterruptedException {
        List<String> cmd = new ArrayList<>();
        cmd.add(javaBin());
        cmd.add("-cp");
        cmd.add(classpath());
        cmd.add(mainClass);
        cmd.addAll(args);

        ProcessBuilder pb = new ProcessBuilder(cmd);
        // 合并 stderr —— 避免管道写满导致子进程卡死
        pb.redirectErrorStream(true);
        Process process = pb.start();
        String output;
        try (InputStream in = process.getInputStream()) {
            output = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        int rc = process.waitFor();
        if (rc != 0) {
            throw new IllegalStateException("探针进程失败 (rc=" + rc + "): " + output);
        }
        return output;
    }

    /**
     * 从子进程输出里捞出带 marker 的那一行（取最后一行 —— 后出现的覆盖先出现的）。
     *
     * @return marker 之后的内容；没有则返回 {@code null}
     */
    public static String marker(String output, String marker) {
        String found = null;
        for (String line : output.split("\\R")) {
            int idx = line.indexOf(marker);
            if (idx >= 0) {
                found = line.substring(idx + marker.length()).trim();
            }
        }
        return found;
    }
}
