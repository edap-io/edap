package io.edap.jni;

import io.edap.log.Logger;
import io.edap.log.LoggerManager;
import io.edap.util.FileUtil;

import java.io.File;
import java.io.InputStream;
import java.util.Locale;
import java.util.Properties;
import java.util.UUID;

/**
 * edap-native 加载入口：运行时按当前 os.arch 解析预编译的 .o 文件并通过 System.load 加载。
 *
 * <p>对标 edap-nio-native 的加载模式：每个平台预编译为 {@code edap-native-{os}_{arch}.o}，
 * 提交到 {@code src/main/resources/}，运行时写到临时目录后加载。</p>
 *
 * <p><b>支持的平台</b>：
 * <ul>
 *   <li>macos x86_64 / aarch64</li>
 *   <li>linux x86_64 / aarch64</li>
 * </ul>
 * 不在以上列表时 {@link #ENABLE_NATIVE} 置为 false，所有 native 调用 fallback 到 Java 实现。</p>
 *
 * <p>每个子模块（如 {@link io.edap.native.crypto.NativeHmacSha256}）在自己的
 * {@code static {}} 里调用 {@link #loadLibrary()}；多模块复用一份 .o，避免重复加载。</p>
 */
public final class Native {

    private static final Logger LOG = LoggerManager.getLogger(Native.class);

    /** 是否成功加载 native 库；false 时所有 native 调用应 fallback 到 Java 实现 */
    public static volatile boolean ENABLE_NATIVE = false;

    private static final Object LOCK = new Object();
    private static volatile boolean initialized = false;

    private Native() {
        // 工具类
    }

    /**
     * 加载 native 库（幂等）。首次调用按当前 os.arch 解析 .o 文件并 System.load；
     * 后续调用直接返回。
     */
    public static void loadLibrary() {
        if (initialized) {
            return;
        }
        synchronized (LOCK) {
            if (initialized) {
                return;
            }
            doLoad();
            initialized = true;
        }
    }

    private static void doLoad() {
        Properties props = System.getProperties();
        String os = ((String) props.get("os.name")).toUpperCase(Locale.ENGLISH);
        String arch = ((String) props.get("os.arch")).toLowerCase(Locale.ENGLISH);

        if (os.contains("MAC")) {
            os = "macos";
        } else if (os.contains("SUNOS")) {
            os = "SunOS";
        } else {
            os = "linux";
            if ("amd64".equals(arch)) {
                arch = "x86_64";
            }
        }
        String libPath = "/edap-native-" + os + "_" + arch + ".o";

        try (InputStream in = Native.class.getResourceAsStream(libPath)) {
            if (in == null) {
                LOG.info("edap-native not built for os=" + os + " arch=" + arch + ", skipping native load");
                ENABLE_NATIVE = false;
                return;
            }
            File tmp = File.createTempFile("edap-native-" + UUID.randomUUID(), ".o");
            FileUtil.inputStreamToFile(in, tmp);
            System.load(tmp.getAbsolutePath());
            tmp.delete();
            ENABLE_NATIVE = true;
            LOG.info("edap-native loaded: os=" + os + " arch=" + arch);
        } catch (Throwable e) {
            LOG.warn("edap-native load failed for os=" + os + " arch=" + arch);
            ENABLE_NATIVE = false;
        }
    }
}
