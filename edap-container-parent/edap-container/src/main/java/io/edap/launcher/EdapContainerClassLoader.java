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
 * 设计思路(对齐 Spring Boot 的 LaunchedURLClassLoader):
 *  - 持有 NestedJarFile(根 jar + 嵌套 jar 集合),直接在 findClass 里读字节,
 *    不走 URLClassLoader 的 URL 协议链路。
 *  - 资源加载(findResource / findResources)用 {@code nested:} 自定义协议,
 *    通过 java.protocol.handler.pkgs 注册 io.edap.launcher.nested.Handler。
 *    Handler.openConnection 剥掉 Java 17 Multi-Release JAR 检测加的 #runtime 片段,
 *    然后 NestedUrlConnection 直接从 NestedJarFile 读字节,绕过 JDK
 *    {@code jar:file:/outer!/inner!/resource} 不支持嵌套 jar 读取的限制
 *    (典型场景:三方 jar 的 META-INF/services/java.sql.Driver SPI 注册)。
 *  - 静态初始化兜底注册 nested: handler:即便应用不走 JarLauncher 入口,
 *    EdapContainerClassLoader 第一次被加载时也会把 handler 加进系统属性,
 *    保证后续构造 nested: URL 时能找到 Handler(否则 MalformedURLException
 *    被默默 catch,findResource 返回 null,ServiceLoader 收不到 SPI 文件,
 *    postgresql 等三方 driver 注册失败)。
 *  - SPI 加载走标准 ServiceLoader 链路:JarLauncher 启动时把 TCCL 设为本 ClassLoader,
 *    业务代码 / DriverManager.ensureDriversInitialized() 调 ServiceLoader.load(Foo.class)
 *    用 TCCL 透过 findResources 拿到嵌套 jar 里的 META-INF/services/* 文件,
 *    自动 Class.forName 触发 static block 完成自注册——和 Spring Boot 的
 *    LaunchedURLClassLoader 行为一致,不需要显式 preload。
 */
public class EdapContainerClassLoader extends URLClassLoader {

    /**
     * 静态初始化:兜底注册 {@code nested:} URL 协议 Handler。
     *
     * <p>{@code java.protocol.handler.pkgs} 必须在首次构造 {@code nested:} URL 之前
     * 设置好,JDK 才会按"包路径 + .{协议名}.Handler"约定反射加载
     * {@code io.edap.launcher.nested.Handler}。JarLauncher 入口路径会在 main()
     * 顶部设置该属性,但 edap 应用也可能不走 JarLauncher(单元测试 / IDE 直接启动 /
     * 其他入口点),那些路径下 {@code findResource} 返回的 {@code nested:} URL 会
     * 抛 {@code MalformedURLException} 被静默吞掉,ServiceLoader 收不到
     * {@code META-INF/services/*} 文件,postgresql 等三方 driver 永远注册不到
     * {@code DriverManager},HikariDataSource 找不到 Driver。</p>
     *
     * <p>本 static block 在 EdapContainerClassLoader 类被任何代码首次引用时触发
     * (即父 ClassLoader 第一次尝试 loadClass 子路径或 newInstance() 本类时),基本
     * 早于所有 {@code new URL("nested:...")} 调用。</p>
     */
    static {
        String current = System.getProperty("java.protocol.handler.pkgs", "");
        if (!current.contains("io.edap.launcher")) {
            String next = current.isEmpty() ? "io.edap.launcher" : current + "|io.edap.launcher";
            System.setProperty("java.protocol.handler.pkgs", next);
        }
    }

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

    /**
     * 构造 {@code nested:} URL——JDK 原生 {@code jar:file:/outer!/inner!/resource}
     * 在嵌套 jar 读取上抛 "JAR entry not found in jar file",{@code nested:} 协议由
     * {@code io.edap.launcher.nested.Handler} 处理,直接从 NestedJarFile 读字节,
     * 能正确读取嵌套 jar 内的资源(ServiceLoader 的 SPI 文件典型场景)。
     */
    private URL nestedUrl(String entry, String resource) throws java.net.MalformedURLException {
        return new URL("nested:" + rootAbsPath + "!/" + entry + "!/" + resource);
    }

    /** 资源加载:聚合根 + 所有 lib jars,支持 ServiceLoader / getResource。 */
    @Override
    public URL findResource(String name) {
        // 1. 根(业务 class 路径在 classesPrefix 下)——rootAbsPath 与 classesPrefix+name 之间用 !/ 分隔
        //    NestedUrlConnection 解析 URL 时按 !/ 拆:第一段是外层 jar 绝对路径,后续段是嵌套 jar/entry。
        //    若错用 /,NestedUrlConnection 找不到 "!/",会把整段当成 outerJarPath、entrySegments 为空,
        //    connect() 抛 "Empty entry path" → ServiceLoader 包成 ServiceConfigurationError。
        try {
            if (root.hasEntry(classesPrefix + name)) {
                return new URL("nested:" + rootAbsPath + "!/" + classesPrefix + name);
            }
        } catch (Exception ignored) {}

        // 2. lib jars
        for (NestedJarFile lib : libJars) {
            try {
                if (lib.hasEntry(name)) {
                    return nestedUrl(lib.getName(), name);
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

        // 1. 根(业务 class 路径在 classesPrefix 下)——见 findResource 同款 !/ 分隔符要求
        try {
            if (root.hasEntry(classesPrefix + name)) {
                v.add(new URL("nested:" + rootAbsPath + "!/" + classesPrefix + name));
            }
        } catch (Exception ignored) {}

        // 2. lib jars
        for (NestedJarFile lib : libJars) {
            try {
                if (lib.hasEntry(name)) {
                    v.add(nestedUrl(lib.getName(), name));
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