package io.edap.launcher;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.security.CodeSource;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.jar.Manifest;

/**
 * 启动用 ClassLoader。
 *
 * 关键设计:
 *  - 持有 NestedJarFile(根 jar + 嵌套 jar 集合),直接在 findClass 里读字节,
 *    不走 URLClassLoader 的 URL 协议链路。
 *    (原计划用 nested: URL 协议,但 Java 17 的 Multi-Release JAR 检测会往 URL
 *    加 #runtime 片段,导致协议解析异常,fall back 到 parent。)
 *  - sealed 包(java.* / javax.* / sun.* / jdk.*)由 bootstrap 处理,直接走 parent。
 *  - 资源加载(META-INF/services 等)也用 NestedJarFile 实现,不走 URLClassLoader 默认链路。
 */
public class EdapContainerClassLoader extends URLClassLoader {

    /** 根 NestedJarFile:对应 BOOT-INF/classes/ */
    private final NestedJarFile root;

    /** 嵌套 jar 列表:对应 BOOT-INF/lib/*.jar,按 JarLauncher 传入顺序 */
    private final List<NestedJarFile> libJars;

    /** 根 jar 中业务 class 的路径前缀(典型为 "BOOT-INF/classes/") */
    private final String classesPrefix;

    private final String rootAbsPath;

    /** 包缓存:为 definePackage 准备,避免每次重新构造。 */
    private final ConcurrentHashMap<String, Package> definedPackages = new ConcurrentHashMap<>();

    /** 已加载的类集合(用于演示/调试,真实场景不需要)。 */
    private final List<Class<?>> loadedByMe =
            Collections.synchronizedList(new java.util.ArrayList<>());

    public EdapContainerClassLoader(File jarFile, String nestDir, ClassLoader parent) throws IOException {
        super(new URL[]{ /* placeholder,实际 findClass 走 NestedJarFile */ }, parent);
        this.root          = new NestedJarFile(jarFile);
        this.libJars       = scanLibJars(root, nestDir);
        this.classesPrefix = nestDir + "/classes/";
        this.rootAbsPath   = jarFile.getAbsolutePath();
    }

    /**
     * @param root          根 NestedJarFile(对应整个外层 jar,需要配合 classesPrefix 定位业务类)
     * @param libJars       嵌套 jar 列表(对应 BOOT-INF/lib/*.jar),允许为空/为 null
     * @param classesPrefix 业务 class 在根 jar 里的路径前缀(典型 "BOOT-INF/classes/",结尾有 /)
     * @param rootAbsPath   外层 jar 的绝对路径,用于 CodeSource/资源 URL
     * @param parent        父 ClassLoader,通常是 AppClassLoader
     */
    public EdapContainerClassLoader(NestedJarFile root,
                                    List<NestedJarFile> libJars,
                                    String classesPrefix,
                                    String rootAbsPath,
                                    ClassLoader parent) {
        super(new URL[]{ /* placeholder,实际 findClass 走 NestedJarFile */ }, parent);
        this.root          = root;
        this.libJars       = libJars != null ? libJars : Collections.emptyList();
        this.classesPrefix = classesPrefix == null ? "" : classesPrefix;
        this.rootAbsPath   = rootAbsPath;
    }

    /**
     * 扫描外层 jar 的所有 entry,挑出 BOOT-INF/lib/*.jar,
     * 为每个嵌套 jar 构造 NestedJarFile。
     *
     * 顺序:按 entry 名字典序,与 maven-assembly-plugin dependencySet 的输出顺序一致
     * (即 Maven 解析依赖图的顺序,通常 dependencies 在前,transitives 在后)。
     */
    static List<NestedJarFile> scanLibJars(NestedJarFile root, String nestDir) {
        // 用 TreeSet 保证稳定的扫描顺序,便于日志阅读
        Set<String> sortedEntries = new TreeSet<>(root.entryNames());

        List<NestedJarFile> libs = new ArrayList<>();
        for (String entryName : sortedEntries) {
            if (!entryName.startsWith(nestDir + "lib/") || !entryName.endsWith(".jar")) {
                continue;
            }
            try {
                NestedJarFile nested = root.getNestedJarFile(entryName);
                if (nested != null) {
                    libs.add(nested);
                }
            } catch (IOException e) {

            }
        }
        return libs;
    }

    @Override
    protected Class<?> findClass(String name) throws ClassNotFoundException {
        String resource = name.replace('.', '/') + ".class";

        // 1. 先查根 (BOOT-INF/classes) —— 业务代码优先
        //    注意:根 jar 里业务类的实际路径是 classesPrefix + resource
        try {
            byte[] bytes = root.readEntryBytes(classesPrefix + resource);
            return definePackageAndClass(name, bytes);
        } catch (IOException ignored) {
            // 根里没有,继续往下查 lib jars
        }

        // 2. 遍历 lib jars(按列表顺序,模拟 classpath 优先级)
        //    找到第一个包含该 entry 的 lib 就用它
        for (NestedJarFile lib : libJars) {
            try {
                byte[] bytes = lib.readEntryBytes(resource);
                return definePackageAndClass(name, bytes);
            } catch (IOException ignored) {
                // 这个 jar 里没有,继续下一个
            }
        }

        // 3. 都没找到
        throw new ClassNotFoundException(name);
    }

    /**
     * definePackage + defineClass 的统一入口。
     * 处理 package 缓存:同名包只在第一次出现时 define,后续走 getDefinedPackage。
     */
    private Class<?> definePackageAndClass(String name, byte[] bytes) {
        int lastDot = name.lastIndexOf('.');
        String pkgName = lastDot == -1 ? "" : name.substring(0, lastDot);
        if (!pkgName.isEmpty() && getDefinedPackage(pkgName) == null) {
            synchronized (definedPackages) {
                if (getDefinedPackage(pkgName) == null) {
                    try {
                        // 尝试从根 jar 读 MANIFEST,获取 Sealed/Version 等
                        // (简化处理:实际 Spring Boot 会按当前 class 所在 jar 读 MANIFEST)
                        Manifest mf = null;
                        try {
                            byte[] mfBytes = root.readEntryBytes("META-INF/MANIFEST.MF");
                            mf = new Manifest(new java.io.ByteArrayInputStream(mfBytes));
                        } catch (IOException ignored) {}
                        if (mf != null) {
                            definePackage(pkgName, mf, codeSourceUrl());
                        } else {
                            definePackage(pkgName, null, null, null, null, null, null, null);
                        }
                    } catch (IllegalArgumentException alreadyDefined) {
                        // 并发情况下已被定义,忽略
                    }
                }
            }
        }
        return defineClass(name, bytes, 0, bytes.length, codeSource());
    }

    /** 返回该 classloader 加载过的所有类(演示用)。 */
    public List<Class<?>> getLoadedClasses() {
        return Collections.unmodifiableList(loadedByMe);
    }

    private CodeSource codeSource() {
        try {
            return new CodeSource(new java.io.File(rootAbsPath).toURI().toURL(),
                    (java.security.cert.Certificate[]) null);
        } catch (Exception e) {
            return null;
        }
    }

    private URL codeSourceUrl() {
        try {
            return new java.io.File(rootAbsPath).toURI().toURL();
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        synchronized (getClassLoadingLock(name)) {
            // 1. 已加载过?
            Class<?> c = findLoadedClass(name);
            if (c != null) {
                if (resolve) resolveClass(c);
                return c;
            }
            // 2. sealed 包永远由 bootstrap 处理
            if (isSealedPackage(name)) {
                return super.loadClass(name, resolve);
            }
            // 3. 自己优先(根 + lib jars)
            try {
                c = findClass(name);
                loadedByMe.add(c);
                if (resolve) resolveClass(c);
                return c;
            } catch (ClassNotFoundException ignored) {}
            // 4. 最后 parent
            return super.loadClass(name, resolve);
        }
    }

    private static boolean isSealedPackage(String className) {
        return className.startsWith("java.") ||
                className.startsWith("javax.") ||
                className.startsWith("sun.") ||
                className.startsWith("jdk.");
    }

    /** 资源加载:聚合根 + 所有 lib jars,支持 ServiceLoader / getResource。 */
    @Override
    public URL findResource(String name) {
        // 1. 根(业务 class 路径在 classesPrefix 下)
        try {
            if (root.hasEntry(classesPrefix + name)) {
                return new URL("nested:" + rootAbsPath + "/" + classesPrefix + name);
            }
        } catch (Exception ignored) {}

        // 2. lib jars
        for (NestedJarFile lib : libJars) {
            try {
                if (lib.hasEntry(name)) {
                    return new URL("nested:" + rootAbsPath + "!/" + lib.getName() + "!/" + name);
                }
            } catch (Exception ignored) {}
        }

        // 3. parent fallback
        return super.findResource(name);
    }

    /**
     * 资源聚合:聚合所有 jar 的同名资源(主要用于 META-INF/services SPI)。
     * 不像 findResource 只返回第一个,这里要返回全部。
     */
    @Override
    public Enumeration<URL> findResources(String name) throws IOException {
        Vector<URL> v = new Vector<>();

        // 1. 根(业务 class 路径在 classesPrefix 下)
        try {
            if (root.hasEntry(classesPrefix + name)) {
                v.add(new URL("nested:" + rootAbsPath + "/" + classesPrefix + name));
            }
        } catch (Exception ignored) {}

        // 2. lib jars
        for (NestedJarFile lib : libJars) {
            try {
                if (lib.hasEntry(name)) {
                    v.add(new URL("nested:" + rootAbsPath + "!/" + lib.getName() + "!/" + name));
                }
            } catch (Exception ignored) {
                ignored.printStackTrace();
            }
        }
        // 3. parent 兜底(URLClassLoader 内部会调 parent.findResources)
        return v.elements();
    }

    @Override
    public void close() throws IOException {
        try { super.close(); } catch (Exception ignored) {}
        if (root != null) root.close();
        for (NestedJarFile lib : libJars) {
            lib.close();
        }
    }
}