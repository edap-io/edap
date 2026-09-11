package io.edap.container.scan;

import io.edap.container.mw.*;
import io.edap.launcher.NestedJarFile;

import java.io.IOException;
import java.io.InputStream;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static io.edap.container.utils.JarUtils.scanBuildInfo;
import static io.edap.container.utils.JarUtils.scanMavenInfo;
import static io.edap.container.utils.ProtoServiceUtils.visitProtoService;

/**
 * edap微服务ear包的扫描器
 */
public class EarScanner {

    /**
     * EAR 内业务 .class 的路径前缀 —— 与 {@code EdapAppPackageMojo} 的
     * {@code classesPrefix = "APP-INF/classes/"} 对齐。
     * <p>修复前(老 plugin bug)classesPrefix=""时业务类散落在 EAR 根,顶层 .endsWith(".class") 直接命中;
     * 修复后业务类统一进 APP-INF/classes/,顶层不再有 .class —— 需要在这里显式枚举 APP-INF/classes/ 下
     * 的 .class,否则 {@link io.edap.container.AppContext#scanBeanDefs} 走 componentMap 路径时
     * 完全看不到业务 @Configuration / @Bean。</p>
     */
    private static final String APP_CLASSES_PREFIX = "APP-INF/classes/";

    private NestedJarFile earFile;

    public static AtomicInteger clazzCount = new AtomicInteger();

    public EarScanner(NestedJarFile earFile) {
        this.earFile = earFile;
    }

    public DeployMetaData scanDeployMetaData() throws IOException {
        DeployMetaData dmd   = new DeployMetaData();
        NestedJarFile  ear   = earFile;
        Set<String>    names = ear.entryNames();
        List<String>   deps  = new ArrayList<>();
        List<ProtoServiceData>   protoServiceInfos = dmd.getProtoServiceInfos();
        Map<String, ServiceMeta> serviceMetaMap    = dmd.getServiceMetaMap();
        Map<String, ConfigurationMetaData> configurationMetaDataMap = dmd.getConfigurationMetaMap();
        for (String name : names) {
            if (name.endsWith("/pom.properties")) {
                clazzCount.addAndGet(1);
                dmd.setMavenInfo(scanMavenInfo(ear, name));
            }
            if (name.equals("META-INF/BUILD.json")) {
                clazzCount.addAndGet(1);
                dmd.setBuildInfo(scanBuildInfo(ear, name));
            }
            if (name.endsWith(".jar")) {
                deps.add(name);
            }
            if (name.endsWith(".class")) {
                try (InputStream in = ear.getInputStream(name)) {
                    clazzCount.addAndGet(1);
                    ProtoServiceData psi = visitProtoService(in, serviceMetaMap, configurationMetaDataMap);
                    if (psi != null) {
                        protoServiceInfos.add(psi);
                    }
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
        }
        Map<String, DeployComponent> componentMap = new HashMap<>();
        for (String name : deps) {
            NestedJarScanner njs = new NestedJarScanner(ear.getNestedJarFile(name));
            DeployComponent dc = njs.scan();
            if (dc != null) {
                MavenInfo mavenInfo = dc.getMavenInfo();
                String artifact;
                if (mavenInfo == null) {
                    artifact = name;
                } else {
                    artifact = mavenInfo.getArtifact();
                }
                componentMap.put(artifact, dc);
            }
        }
        // APP-INF/classes/ 下的业务 .class 不在 deps(它们不是 .jar),也不被顶层 endsWith(".class")
        // 命中(顶层入口名是 APP-INF/classes/io/.../X.class,但前面修复 packaging 后顶层根本不再
        // 命中 .class,因为业务类统一进 APP-INF/classes/)。
        // 这里构造一个虚拟 DeployComponent 把这些 .class 的扫描结果挂进 componentMap,
        // 让 AppContext.scanBeanDefs(componentMap 遍历)能看到 @Configuration / @Bean。
        DeployComponent appClasses = scanAppClasses(ear, names);
        if (appClasses != null) {
            componentMap.put(APP_CLASSES_PREFIX, appClasses);
        }
        dmd.setComponentMap(componentMap);

        return dmd;
    }

    /**
     * 扫 {@link #APP_CLASSES_PREFIX} 下的 .class,产出 DeployComponent。
     * 行为对齐 NestedJarScanner:每个 .class 都 visitProtoService,挂到 DeployComponent 的
     * serviceMetaMap / configurationMetaMap / protoServiceInfos。
     */
    private DeployComponent scanAppClasses(NestedJarFile ear, Set<String> names) throws IOException {
        DeployComponent dc = new DeployComponent();
        Map<String, ServiceMeta> serviceMetaMap = dc.getServiceMetaMap();
        Map<String, ConfigurationMetaData> configurationMetaMap = dc.getConfigurationMetaMap();
        List<ProtoServiceData> psiList = dc.getProtoServiceInfos();
        boolean any = false;
        for (String name : names) {
            if (!name.startsWith(APP_CLASSES_PREFIX) || !name.endsWith(".class")) {
                continue;
            }
            any = true;
            try (InputStream in = ear.getInputStream(name)) {
                clazzCount.addAndGet(1);
                ProtoServiceData psi = visitProtoService(in, serviceMetaMap, configurationMetaMap);
                if (psi != null) {
                    psiList.add(psi);
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
        return any ? dc : null;
    }

}
