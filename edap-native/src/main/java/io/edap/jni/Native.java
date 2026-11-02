package io.edap.jni;

import io.edap.log.Logger;
import io.edap.log.LoggerManager;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
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
 * <p>每个子模块（如 {@link io.edap.jni.crypto.NativeHmacSha256}）在自己的
 * {@code static {}} 里调用 {@link #loadLibrary()}；多模块复用一份 .o，避免重复加载。</p>
 *
 * <p><b>为什么本类不依赖 edap-common</b>：edap-crypto 会依赖 edap-native（test scope），
 * 而 edap-common 也要依赖 edap-crypto，形成 Maven reactor 环。本类原本只用到
 * {@code io.edap.util.FileUtil.inputStreamToFile}（10 行），改为内联实现即可解开这个环。</p>
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
        String osName   = (String) props.get("os.name");
        String archName = (String) props.get("os.arch");

        String os   = normalizeOs(osName);
        String arch = normalizeArch(archName);
        if ("unsupported".equals(os)) {
            LOG.info("edap-native unsupported os=" + osName + ", skipping native load");
            ENABLE_NATIVE = false;
            return;
        }

        // 资源名必须带前导斜杠：Class.getResourceAsStream 对以 "/" 开头的是绝对路径语义，
        // 不带斜杠会按本类所在包（io/edap/jni/）相对查找，必然取不到。
        String libPath = "/edap-native-" + os + "_" + arch + ".o";

        try (InputStream in = Native.class.getResourceAsStream(libPath)) {
            if (in == null) {
                LOG.info("edap-native not built for os=" + os + " arch=" + arch + ", skipping native load");
                ENABLE_NATIVE = false;
                return;
            }
            File tmp = File.createTempFile("edap-native-" + UUID.randomUUID(), ".o");
            copy(in, tmp);
            System.load(tmp.getAbsolutePath());
            // 删不掉也不能影响 native 可用性，登记 deleteOnExit 兜底
            if (!tmp.delete()) {
                tmp.deleteOnExit();
            }
            ENABLE_NATIVE = true;
            LOG.info("edap-native loaded: os=" + os + " arch=" + arch);
        } catch (Throwable e) {
            LOG.warn("edap-native load failed for os=" + os + " arch=" + arch);
            ENABLE_NATIVE = false;
        }
    }

    /**
     * {@code os.name} → 资源名里的 os 段。
     *
     * <p>旧实现只区分 MAC / SUNOS，其余一律落到 linux —— Windows 会被误判成 linux，
     * amd64→x86_64 的归一化也只在 linux 分支内做。这里改成白名单映射，
     * 不认识的平台返回 {@code "unsupported"}，调用方直接跳过加载。</p>
     */
    public static String normalizeOs(String osName) {
        String os = osName == null ? "" : osName.toUpperCase(Locale.ENGLISH);
        if (os.contains("MAC") || os.contains("DARWIN")) {
            return "macos";
        }
        if (os.contains("WIN")) {
            return "windows";
        }
        if (os.contains("LINUX")) {
            return "linux";
        }
        if (os.contains("SUNOS") || os.contains("SOLARIS")) {
            return "sunos";
        }
        if (os.contains("FREEBSD")) {
            return "freebsd";
        }
        return "unsupported";
    }

    /**
     * {@code os.arch} → 资源名里的 arch 段。归一化对所有平台一视同仁（旧实现只在 linux 分支里做）。
     */
    public static String normalizeArch(String archName) {
        String arch = archName == null ? "" : archName.toLowerCase(Locale.ENGLISH);
        switch (arch) {
            case "amd64":
            case "x86_64":
                return "x86_64";
            case "aarch64":
            case "arm64":
                return "aarch64";
            case "x86":
            case "i386":
            case "i486":
            case "i586":
            case "i686":
                return "x86";
            default:
                return arch;
        }
    }

    /**
     * 把资源流落盘到临时文件。
     *
     * <p>内联自 {@code io.edap.util.FileUtil.inputStreamToFile}：edap-native 不依赖 edap-common，
     * 否则 edap-common → edap-crypto →(test) edap-native → edap-common 会形成 Maven reactor 环。</p>
     */
    private static void copy(InputStream in, File file) throws IOException {
        try (OutputStream out = new FileOutputStream(file)) {
            byte[] buf = new byte[4096];
            int len = in.read(buf);
            while (len != -1) {
                out.write(buf, 0, len);
                len = in.read(buf);
            }
        }
    }
}
