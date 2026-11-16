package io.edap.jni.test;

import io.edap.jni.Native;
import io.edap.jni.Platform;
import org.junit.jupiter.api.Test;

import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * os/arch 归一化：资源名 {@code edap-native-{os}_{arch}.o} 的规则只有一份 ——
 * {@link io.edap.jni.Platform}；运行时（Native）与构建脚本（build-native.sh）都用它。
 *
 * <p>旧实现只有 MAC / SUNOS 两个分支，其余一律落进 linux —— Windows 会被当成 linux 去加载，
 * amd64→x86_64 的归一化也只在 linux 分支内做，macOS 上会去找 {@code macos_amd64.o}（不存在）。</p>
 */
public class NativeOsArchTest {

    @Test
    public void testNormalizeOs() {
        assertEquals("macos", Native.normalizeOs("Mac OS X"));
        assertEquals("macos", Native.normalizeOs("macOS"));
        assertEquals("macos", Native.normalizeOs("Darwin"));
        assertEquals("linux", Native.normalizeOs("Linux"));
        assertEquals("windows", Native.normalizeOs("Windows 10"));
        assertEquals("windows", Native.normalizeOs("Windows Server 2019"));
        assertEquals("sunos", Native.normalizeOs("SunOS"));
        assertEquals("sunos", Native.normalizeOs("Solaris"));
        assertEquals("freebsd", Native.normalizeOs("FreeBSD"));
        assertEquals("unsupported", Native.normalizeOs("Plan 9"));
        assertEquals("unsupported", Native.normalizeOs(""));
        assertEquals("unsupported", Native.normalizeOs(null));
    }

    @Test
    public void testNormalizeArch() {
        assertEquals("x86_64", Native.normalizeArch("amd64"));
        assertEquals("x86_64", Native.normalizeArch("x86_64"));
        assertEquals("aarch64", Native.normalizeArch("aarch64"));
        assertEquals("aarch64", Native.normalizeArch("arm64"));
        assertEquals("x86", Native.normalizeArch("x86"));
        assertEquals("x86", Native.normalizeArch("i386"));
        assertEquals("x86", Native.normalizeArch("i686"));
        assertEquals("ppc64le", Native.normalizeArch("ppc64le"));
        assertEquals("", Native.normalizeArch(null));
    }

    /**
     * 资源名必须带前导斜杠：{@code Class.getResourceAsStream} 对以 "/" 开头的按绝对路径查找，
     * 不带斜杠则按本类所在包（io/edap/jni/）相对查找，必然取不到。
     */
    @Test
    public void testResourcePathNeedsLeadingSlash() {
        String os   = Native.normalizeOs(System.getProperty("os.name"));
        String arch = Native.normalizeArch(System.getProperty("os.arch"));
        String path = "/edap-native-" + os + "_" + arch + ".o";
        assertTrue(path.startsWith("/"), "资源名必须以 / 开头，否则 getResourceAsStream 按包名相对查找");

        // 当前平台如果预编译过 .o，带斜杠必须能取到；不带斜杠取不到
        if (Native.class.getResource(path) != null) {
            try (InputStream in = Native.class.getResourceAsStream(path)) {
                assertNotNull(in, "带前导斜杠的绝对路径必须能加载到预编译产物");
            } catch (Exception e) {
                throw new AssertionError(e);
            }
            assertNull(Native.class.getResourceAsStream(path.substring(1)),
                    "不带前导斜杠会按 io/edap/jni/ 相对查找，取不到才对");
        }
    }

    /**
     * SPARC 的 os.arch 有多种形态，<b>必须原样透传</b>。
     *
     * <p>Solaris 上 {@code uname -m} 恒为 {@code sun4v}，而 JVM 的 {@code os.arch} 通常是
     * {@code sparcv9}（64 位 JDK）—— 两者不是一回事。产物名按 JVM 的 os.arch 算，
     * 运行时也按 os.arch 找，两边一致才找得到。</p>
     */
    @Test
    public void testSparcArchVariantsPassThrough() {
        assertEquals("sparcv9", Platform.normalizeArch("sparcv9"));
        assertEquals("sparc", Platform.normalizeArch("sparc"));
        assertEquals("sun4v", Platform.normalizeArch("sun4v"));
        // 大小写不敏感
        assertEquals("sparcv9", Platform.normalizeArch("SPARCV9"));
    }

    /**
     * 资源名 = edap-native-{os}_{arch}.o —— 运行时与构建脚本必须算出同一个名字。
     */
    @Test
    public void testResourceName() {
        assertEquals("edap-native-sunos_sparcv9.o", Platform.resourceName("SunOS", "sparcv9"));
        assertEquals("edap-native-sunos_sun4v.o", Platform.resourceName("SunOS", "sun4v"));
        assertEquals("edap-native-macos_aarch64.o", Platform.resourceName("Mac OS X", "arm64"));
        assertEquals("edap-native-linux_x86_64.o", Platform.resourceName("Linux", "amd64"));
        assertTrue(Platform.resourcePath("SunOS", "sparcv9").startsWith("/"),
                "资源路径必须以 / 开头");
    }

    /**
     * Native 的归一化必须与 Platform 完全一致（Native 只是转发，规则只有一份）。
     */
    @Test
    public void testNativeDelegatesToPlatform() {
        String[] osNames = {"SunOS", "Solaris", "Linux", "Mac OS X", "Windows 11"};
        String[] arches = {"sparcv9", "sun4v", "amd64", "arm64", "x86"};
        for (String os : osNames) {
            assertEquals(Platform.normalizeOs(os), Native.normalizeOs(os),
                    "os 归一化必须与 Platform 一致: " + os);
            for (String arch : arches) {
                assertEquals(Platform.normalizeArch(arch), Native.normalizeArch(arch),
                        "arch 归一化必须与 Platform 一致: " + arch);
            }
        }
    }
}
