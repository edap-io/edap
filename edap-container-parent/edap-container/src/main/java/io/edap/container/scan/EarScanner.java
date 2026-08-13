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
        dmd.setComponentMap(componentMap);

        return dmd;
    }

}
