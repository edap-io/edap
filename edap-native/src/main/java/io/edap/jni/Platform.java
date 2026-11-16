package io.edap.jni;

import java.util.Locale;

/**
 * 平台归一化 —— <b>预编译产物资源名规则的唯一事实源</b>。
 *
 * <p>运行时（{@link Native#doLoad()}）与构建脚本（{@code scripts/build-native.sh}）
 * <b>必须用同一份规则</b>，否则「编译出来的是 {@code edap-native-sunos_sparcv9.o}、
 * 运行时找的是 {@code edap-native-sunos_sun4v.o}」⇒ native 静默不加载，
 * 所有加密悄悄退回 JVM 内置实现 —— 这类问题最难发现（性能悄悄变差，功能全对）。
 * </p>
 *
 * <p><b>本类刻意零依赖</b>（只用 {@code java.util.Locale}）：构建脚本在编译 .o 之前
 * 需要先算出产物名，那时 edap-native 还没打成 jar、edap-log-api 也不在 classpath 上。
 * 零依赖 ⇒ 脚本可以直接用 {@code javac} 编译本文件并运行 {@link #main(String[])}，
 * 拿到的名字与运行时<b>逐字节一致</b>，不可能漂移。</p>
 *
 * <p><b>为什么不能用 uname</b>：Solaris/SPARC 上 {@code uname -m} 恒为 {@code sun4v}，
 * 而 JVM 的 {@code os.arch} 是 {@code sparcv9}（64 位 JDK）或 {@code sparc}（32 位）——
 * 两者不是一回事，按 uname 取名必然对不上。</p>
 */
public final class Platform {

    /** 不认识的平台。 */
    public static final String UNSUPPORTED = "unsupported";

    private Platform() {
        // 工具类
    }

    /**
     * {@code os.name} → 资源名里的 os 段。
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
        return UNSUPPORTED;
    }

    /**
     * {@code os.arch} → 资源名里的 arch 段。
     *
     * <p><b>默认分支是「原样透传」而不是拒绝</b>：Solaris/SPARC 的 {@code os.arch}
     * 可能是 {@code sparcv9} / {@code sparc} / {@code sun4v}，取决于 JDK 版本与位数。
     * 只要「运行时算出来的名字」与「构建出来的名字」是同一个算法算出来的，
     * 具体值是什么都无所谓 —— 透传比硬编码白名单更不容易漂移。</p>
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
     * 预编译产物的<b>文件名</b>（不含前导斜杠），例如
     * {@code edap-native-sunos_sparcv9.o}。
     */
    public static String resourceName(String osName, String archName) {
        return "edap-native-" + normalizeOs(osName) + "_" + normalizeArch(archName) + ".o";
    }

    /**
     * 预编译产物的<b>资源路径</b>（带前导斜杠）。
     *
     * <p>必须带前导斜杠：{@code Class.getResourceAsStream} 对以 "/" 开头的是绝对路径语义，
     * 不带斜杠会按本类所在包（io/edap/jni/）相对查找，必然取不到。</p>
     */
    public static String resourcePath(String osName, String archName) {
        return "/" + resourceName(osName, archName);
    }

    /**
     * 打印当前 JVM 的平台信息 —— <b>给构建脚本用</b>。
     *
     * <p>输出 {@code key=value} 形式，构建脚本据此决定产物名与编译参数：</p>
     * <pre>
     * os.name=SunOS
     * os.arch=sparcv9
     * os=sunos
     * arch=sparcv9
     * name=edap-native-sunos_sparcv9.o
     * </pre>
     */
    public static void main(String[] args) {
        String osName = System.getProperty("os.name", "");
        String osArch = System.getProperty("os.arch", "");
        System.out.println("os.name=" + osName);
        System.out.println("os.arch=" + osArch);
        System.out.println("os=" + normalizeOs(osName));
        System.out.println("arch=" + normalizeArch(osArch));
        System.out.println("name=" + resourceName(osName, osArch));
    }
}
