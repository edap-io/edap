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

package io.edap.image.vips;

import io.edap.image.ImageEngine;
import io.edap.image.ImageFormat;
import io.edap.image.ImageProcessingException;
import io.edap.image.ThumbSpec;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * 基于 libvips <b>命令行</b>的缩略图引擎（每个请求拉起子进程执行 {@code vips}）。
 *
 * <p><b>为什么用命令行而不是进程内（JNA/JNI）调用</b>——决定性因素是崩溃隔离，不是性能：</p>
 * <ul>
 *   <li>进程内调用时，绑定代码里的任何错误（我实测踩过 3 次：把
 *       {@code vips_image_get_width} 当成 out 参数版写，符号直接崩在
 *       {@code libvips.so.42+0x18ec84}）都会让<b>整个 JVM 无异常地死掉</b>，
 *       Java 层 catch 不到。在 edap-s3 里那等于 S3 全站不可用，不只是缩略图挂掉。
 *       子进程崩则只是一次派生失败。</li>
 *   <li>内存归还：子进程退出，内存立刻还给 OS；进程内的堆外内存是 JVM 的盲区
 *       （不受 {@code -Xmx} 约束），实测不显式释放能涨到 7.4 GB。</li>
 *   <li>部署成本并不更高：两种方式都需要 libvips 装在跑 edap-s3 的机器上，
 *       JNA 额外还要 jnidispatch 临时目录（Solaris 的 /tmp 常常 noexec）。</li>
 * </ul>
 *
 * <p>性能代价实测（Debian 13 aarch64，4000×3000 JPEG → 200px 宽）：</p>
 * <ul>
 *   <li>进程内 42 ms vs 命令行 73 ms（含 25 ms 的 libvips 动态链接开销，
 *       这是每次 fork 的固定成本，libvips 8.16 没有常驻 server 模式，省不掉）；</li>
 *   <li>峰值 RSS：命令行 38.7 MB（子进程），IM/GM 是 108~228 MB；</li>
 *   <li>8 并发吞吐：进程内 146 张/s vs 命令行 86 张/s。缩略图是异步派生、
 *       不在请求路径上，这 1.7 倍的差距可以接受。</li>
 * </ul>
 *
 * <p><b>命令行调用链</b>（两步，因为质量参数只在编码步骤上）：</p>
 * <pre>
 * vips --vips-max-coord=C thumbnail in.jpg mid.v &lt;width&gt; [--height=H] --size=down --fail-on=error
 * vips jpegsave|webpsave mid.v out.jpg --Q=&lt;quality&gt;
 * </pre>
 *
 * <p>几个必须记住的命令行坑（都是实测踩出来的）：</p>
 * <ul>
 *   <li><b>不能用 {@code vipsthumbnail}</b>：它没有 no-upscale 开关（小图会被放大），
 *       不支持 stdin，质量固定 Q75。要「只缩不放」只能用
 *       {@code vips thumbnail --size=down}。</li>
 *   <li><b>stdin 不支持</b>：{@code vipsthumbnail} 读 stdin 会静默成功但不产出任何文件，
 *       {@code vips thumbnail -} 直接报「file "-" does not exist」。所以必须落临时文件。</li>
 *   <li><b>一次只能出一个规格</b>：多个 {@code -s} / {@code -o} 只有最后一个生效。</li>
 *   <li><b>默认会放过截断文件</b>：{@code --fail-on} 默认是 {@code none}，
 *       截断的 JPEG 会 rc=0 通过，必须显式加 {@code --fail-on=error}。</li>
 * </ul>
 *
 * <p><b>libvips 是运行期依赖</b>：{@code vips} 可执行文件找不到时
 * {@link #available()} 返回 false，模块照常编译启动，调用方自行降级。</p>
 *
 * <p>本类<b>线程安全</b>，无共享可变状态。</p>
 */
public final class VipsCliImageEngine implements ImageEngine {

    /**
     * 引擎标识 —— 参与派生资源缓存目录名。
     *
     * <p>{@code v1} 是「输出字节约定」的版本号：改了编码参数默认值、换了 libvips
     * 大版本、或者换了调用方式（命令行 ↔ 进程内），产物字节就可能变 ⇒ 必须把 v1 往上推，
     * 否则旧缓存会被当成本引擎的产物继续用。推 v1 的代价只是旧缓存作废重算。</p>
     */
    public static final String ENGINE_ID = "libvips-cli-v1";

    /** 覆盖 libvips 命令行位置的系统属性，例如 {@code -Dedap.image.vips.cli=/opt/libvips/bin/vips}。 */
    public static final String BINARY_PROPERTY = "edap.image.vips.cli";

    /** 覆盖临时工作目录的系统属性，例如 {@code -Dedap.image.vips.tempdir=/export/tmp}。 */
    public static final String TEMP_DIR_PROPERTY = "edap.image.vips.tempdir";

    /** 输入图像字节数上限，超过直接拒绝。 */
    public static final int DEFAULT_MAX_INPUT_BYTES = 32 * 1024 * 1024;

    /** 输入图像像素数上限（宽×高），超过直接拒绝。 */
    public static final long DEFAULT_MAX_PIXELS = 50_000_000L;

    /** 单个子进程的执行超时（秒）。 */
    public static final long DEFAULT_TIMEOUT_SECONDS = 30L;

    /** stderr 里最多读取多少字符用于报错，超出截断。 */
    private static final int MAX_ERROR_CHARS = 4096;

    /** {@code vips} 命令行在 PATH 下的候选文件名。 */
    private static final String[] BINARY_NAMES = {"vips", "vips.exe"};

    private final Path binary;
    private final String failure;
    private final Path tempRoot;
    private final int maxInputBytes;
    private final long maxPixels;
    private final long timeoutSeconds;

    public VipsCliImageEngine() {
        this(DEFAULT_MAX_INPUT_BYTES, DEFAULT_MAX_PIXELS, DEFAULT_TIMEOUT_SECONDS);
    }

    /**
     * @param maxInputBytes  输入字节数上限，必须大于 0
     * @param maxPixels      输入像素数上限（宽×高），必须大于 0
     * @param timeoutSeconds 单个子进程执行超时（秒），必须大于 0
     */
    public VipsCliImageEngine(int maxInputBytes, long maxPixels, long timeoutSeconds) {
        if (maxInputBytes <= 0) {
            throw new IllegalArgumentException("maxInputBytes 必须大于 0: " + maxInputBytes);
        }
        if (maxPixels <= 0) {
            throw new IllegalArgumentException("maxPixels 必须大于 0: " + maxPixels);
        }
        if (timeoutSeconds <= 0) {
            throw new IllegalArgumentException("timeoutSeconds 必须大于 0: " + timeoutSeconds);
        }
        this.maxInputBytes = maxInputBytes;
        this.maxPixels = maxPixels;
        this.timeoutSeconds = timeoutSeconds;

        Path resolved = resolveBinary();
        this.binary = resolved;
        this.failure = resolved == null ? "找不到 vips 命令行，请安装 libvips-tools 或设置 "
                + BINARY_PROPERTY : null;

        String tempDir = System.getProperty(TEMP_DIR_PROPERTY);
        this.tempRoot = tempDir == null || tempDir.trim().isEmpty()
                ? Paths.get(System.getProperty("java.io.tmpdir"))
                : Paths.get(tempDir.trim());
    }

    @Override
    public String id() {
        return ENGINE_ID;
    }

    @Override
    public boolean available() {
        return binary != null;
    }

    /**
     * libvips 命令行不可用时的原因。
     *
     * @return 失败原因；可用时为 null
     */
    public String unavailableReason() {
        return failure;
    }

    @Override
    public byte[] thumbnail(byte[] source, ThumbSpec spec) {
        if (spec == null) {
            throw new IllegalArgumentException("spec 不能为 null");
        }
        if (!available()) {
            throw new ImageProcessingException("libvips 命令行不可用: " + failure);
        }
        if (source == null || source.length == 0) {
            throw new ImageProcessingException("输入图像为空");
        }
        if (source.length > maxInputBytes) {
            throw new ImageProcessingException("输入图像过大: " + source.length + " > " + maxInputBytes);
        }
        String extension = detectInputExtension(source);
        if (extension == null) {
            throw new ImageProcessingException("不支持的输入格式，仅接受 JPEG/PNG/WebP/GIF");
        }

        Path workDir = null;
        try {
            workDir = Files.createTempDirectory(tempRoot, "edap-thumb-");
            Path input = workDir.resolve("in." + extension);
            Path scaled = workDir.resolve("scaled.v");
            Path output = workDir.resolve(spec.format() == ImageFormat.WEBP ? "out.webp" : "out.jpg");
            Files.write(input, source);

            List<String> resize = new ArrayList<>(8);
            resize.add(binary.toString());
            // 解码炸弹闸门：超过该边长的图直接加载失败。取边长上限使 宽×高 ≤ maxPixels。
            // JPEG 走 shrink-on-load，解码出来的尺寸比原图小，这个闸门对它天然更宽松。
            resize.add("--vips-max-coord=" + maxCoord());
            resize.add("thumbnail");
            resize.add(input.toString());
            resize.add(scaled.toString());
            resize.add(String.valueOf(spec.width()));
            if (spec.height() != null) {
                resize.add("--height=" + spec.height());
            }
            // 只缩不放
            resize.add("--size=down");
            // 默认 fail-on=none 会放过截断文件，必须显式改成 error
            resize.add("--fail-on=error");
            exec(resize, workDir, "resize", "缩放失败");

            List<String> encode = new ArrayList<>(5);
            encode.add(binary.toString());
            encode.add(spec.format() == ImageFormat.WEBP ? "webpsave" : "jpegsave");
            encode.add(scaled.toString());
            encode.add(output.toString());
            encode.add("--Q=" + spec.quality());
            exec(encode, workDir, "encode", "编码失败");

            byte[] result = Files.readAllBytes(output);
            if (!spec.format().matches(result)) {
                throw new ImageProcessingException("产物格式校验失败，期望 " + spec.format());
            }
            return result;
        } catch (IOException e) {
            throw new ImageProcessingException("缩略图处理失败: " + e.getMessage(), e);
        } finally {
            deleteRecursively(workDir);
        }
    }

    /**
     * 边长上限，使解码后的宽×高不超过 {@link #maxPixels}。
     *
     * @return 传给 {@code --vips-max-coord} 的边长
     */
    private long maxCoord() {
        return Math.max(1L, (long) Math.sqrt(maxPixels));
    }

    /**
     * 执行一个子进程，带超时。
     *
     * <p>stderr 重定向到文件而不是管道：管道写满而没人读会让子进程卡死，
     * 而畸形文件恰恰是最容易产生大量输出的。</p>
     *
     * @param command 完整命令行
     * @param workDir 工作目录
     * @param tag     步骤标识，用于区分 stderr 文件
     * @param what    失败时的动作描述
     */
    private void exec(List<String> command, Path workDir, String tag, String what) {
        Path errorFile = workDir.resolve("stderr-" + tag + ".txt");
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        builder.redirectError(errorFile.toFile());

        Process process;
        try {
            process = builder.start();
        } catch (IOException e) {
            throw new ImageProcessingException(what + ": 无法启动 " + command.get(0) + ": " + e.getMessage(), e);
        }
        boolean finished;
        try {
            finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new ImageProcessingException(what + ": 等待子进程时被中断", e);
        }
        if (!finished) {
            process.destroyForcibly();
            throw new ImageProcessingException(what + ": 执行超时（" + timeoutSeconds + " 秒）");
        }
        if (process.exitValue() != 0) {
            throw new ImageProcessingException(what + ": " + readError(errorFile)
                    + "（退出码 " + process.exitValue() + "）");
        }
    }

    /**
     * 读取子进程的 stderr，截断到 {@link #MAX_ERROR_CHARS}。
     *
     * @param errorFile stderr 文件
     * @return 错误文本；读不出来时返回空串
     */
    private static String readError(Path errorFile) {
        try {
            String text = new String(Files.readAllBytes(errorFile), java.nio.charset.StandardCharsets.UTF_8);
            if (text.length() > MAX_ERROR_CHARS) {
                text = text.substring(0, MAX_ERROR_CHARS) + "…";
            }
            return text.trim();
        } catch (IOException e) {
            return "";
        }
    }

    /**
     * 识别输入图像格式。
     *
     * <p>只接受 JPEG/PNG/WebP/GIF。这不只是「友好提示」，而是<b>安全边界</b>：
     * libvips 的 loader 大多按文件内容嗅探，但 SVG loader 是按<b>扩展名</b>匹配的，
     * PDF loader 会去调 poppler —— 两者都是不可信输入的攻击面。在交给 libvips 之前
     * 先用 magic 字节筛掉，攻击面就没了。</p>
     *
     * @param data 输入字节
     * @return 临时文件应使用的扩展名；不在白名单内返回 null
     */
    static String detectInputExtension(byte[] data) {
        if (data == null) {
            return null;
        }
        if (startsWith(data, 0xFF, 0xD8, 0xFF)) {
            return "jpg";
        }
        if (startsWith(data, 0x89, 'P', 'N', 'G')) {
            return "png";
        }
        if (startsWith(data, 'G', 'I', 'F', '8')) {
            return "gif";
        }
        if (data.length >= 12 && startsWith(data, 'R', 'I', 'F', 'F')
                && data[8] == 'W' && data[9] == 'E' && data[10] == 'B' && data[11] == 'P') {
            return "webp";
        }
        return null;
    }

    private static boolean startsWith(byte[] data, int... prefix) {
        if (data.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if ((data[i] & 0xFF) != (prefix[i] & 0xFF)) {
                return false;
            }
        }
        return true;
    }

    /**
     * 定位 {@code vips} 可执行文件。
     *
     * @return 可执行文件路径；找不到返回 null
     */
    private static Path resolveBinary() {
        String override = System.getProperty(BINARY_PROPERTY);
        if (override != null && !override.trim().isEmpty()) {
            Path path = Paths.get(override.trim());
            return isExecutable(path) ? path : null;
        }
        String pathEnv = System.getenv("PATH");
        if (pathEnv == null) {
            return null;
        }
        for (String dir : pathEnv.split(java.io.File.pathSeparator)) {
            if (dir.isEmpty()) {
                continue;
            }
            for (String name : BINARY_NAMES) {
                Path candidate = Paths.get(dir, name);
                if (isExecutable(candidate)) {
                    return candidate;
                }
            }
        }
        return null;
    }

    private static boolean isExecutable(Path path) {
        return Files.isRegularFile(path) && Files.isExecutable(path);
    }

    /**
     * 删除临时工作目录。
     *
     * @param dir 目录；可为 null
     */
    private static void deleteRecursively(Path dir) {
        if (dir == null) {
            return;
        }
        try (Stream<Path> paths = Files.walk(dir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // 临时目录删不掉不影响主流程
                }
            });
        } catch (IOException ignored) {
            // 同上
        }
    }
}
