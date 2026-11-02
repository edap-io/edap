package io.edap.jni.test;

import io.edap.jni.Native;
import org.junit.jupiter.api.Test;

import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * os/arch 归一化：资源名 {@code edap-native-{os}_{arch}.o} 必须与 build-native.sh 的命名一致。
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
}
