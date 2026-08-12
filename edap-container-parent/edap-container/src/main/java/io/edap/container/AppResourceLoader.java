package io.edap.container;

import io.edap.container.exc.NoSuchResourceException;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import java.util.Objects;

/**
 * AppContext 的资源加载器——包装 per-app ClassLoader，提供"以本 AppContext 视角"的资源访问。
 *
 * <p><b>为什么必须有</b>：资源必须走 appCL 而不是 {@code Thread.currentThread().getContextClassLoader()}，
 * 否则不同 AppContext 之间的 jar 资源会互相可见（破坏 per-app 隔离）。</p>
 *
 * <p><b>设计要点</b>：</p>
 * <ul>
 *   <li>资源查找走 appCL：保证"per-app 资源隔离"——appA 看不到 appB 的资源</li>
 *   <li>不做缓存：appCL 自身对 jar entry 有缓存（URLClassPath / Resource）；
 *       AppResourceLoader 不重复缓存，避免多级缓存一致性</li>
 *   <li>资源不存在时 {@link #getResourceAsStream(String)} / {@link #getResource(String)}
 *       返回 null（与 JDK ClassLoader 行为一致）；需要严格语义的场景调
 *       {@link #getBytes(String)} / {@link #getString(String)}，不存在时抛
 *       {@link NoSuchResourceException}</li>
 *   <li>路径规范：使用 ClassLoader 路径（{@code '/'} 分隔），不强制以 {@code '/'} 开头（与 JDK 一致）</li>
 *   <li>流关闭由调用方负责：本类只返回 {@link InputStream}；{@link #getBytes(String)} /
 *       {@link #getString(String)} 内部已 try-with-resources</li>
 * </ul>
 */
public class AppResourceLoader {

    private final ClassLoader appCL;

    public AppResourceLoader(ClassLoader appCL) {
        this.appCL = Objects.requireNonNull(appCL, "appCL");
    }

    /**
     * 加载 classpath 资源，返回的 {@link InputStream} 由调用方 close。
     * @param name ClassLoader 路径（{@code "META-INF/services/io.edap.X"} 等）
     * @return 资源流；资源不存在返回 null
     */
    public InputStream getResourceAsStream(String name) {
        if (name == null || name.isEmpty()) return null;
        return appCL.getResourceAsStream(name);
    }

    /** 加载所有匹配资源（如 {@code META-INF/services/...} 多个实现）。 */
    public Enumeration<URL> getResources(String name) throws IOException {
        if (name == null || name.isEmpty()) return null;
        return appCL.getResources(name);
    }

    /** 加载单个资源 URL（资源不存在返回 null）。 */
    public URL getResource(String name) {
        if (name == null || name.isEmpty()) return null;
        return appCL.getResource(name);
    }

    /**
     * 读资源到字节数组（小资源用，如 SPI 配置 / 短文本）。
     * @throws NoSuchResourceException 资源不存在
     * @throws IOException 读取失败
     */
    public byte[] getBytes(String name) throws IOException {
        try (InputStream in = getResourceAsStream(name)) {
            if (in == null) throw new NoSuchResourceException(name);
            return in.readAllBytes();
        }
    }

    /** 读资源为字符串（UTF-8；资源不存在抛 {@link NoSuchResourceException}）。 */
    public String getString(String name) throws IOException {
        return new String(getBytes(name), StandardCharsets.UTF_8);
    }

    /**
     * 当前 per-app ClassLoader（用于 framework 内部扩展：如 SPI 查找、
     * ASM 生成类定义加载、{@code Class.forName(name, false, appCL)}）。
     */
    public ClassLoader classLoader() {
        return appCL;
    }
}