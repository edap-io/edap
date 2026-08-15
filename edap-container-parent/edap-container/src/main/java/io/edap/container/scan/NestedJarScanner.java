package io.edap.container.scan;

import io.edap.container.mw.*;
import io.edap.launcher.NestedJarFile;
import org.objectweb.asm.*;

import java.io.InputStream;
import java.util.*;

import static io.edap.container.scan.EarScanner.clazzCount;
import static io.edap.container.utils.JarUtils.scanMavenInfo;
import static io.edap.container.utils.ProtoServiceUtils.visitProtoService;

public class NestedJarScanner {

    private NestedJarFile nestedJarFile;

    private DeployComponent tmpDc = new DeployComponent();

    public NestedJarScanner(NestedJarFile nestedJarFile) {
        this.nestedJarFile = nestedJarFile;
    }

    public DeployComponent scan() {
        DeployComponent dc = tmpDc;
        NestedJarFile          njar     = nestedJarFile;
        Set<String>            names    = njar.entryNames();
        List<ProtoServiceData> infoList = dc.getProtoServiceInfos();
        Map<String, ServiceMeta> serviceMetaMap = dc.getServiceMetaMap();
        Map<String, ConfigurationMetaData> configurationMetaDataMap = dc.getConfigurationMetaMap();
        for (String name : names) {
            if (name.startsWith("META-INF") && name.endsWith("/pom.properties")) {
                clazzCount.addAndGet(1);
                dc.setMavenInfo(scanMavenInfo(nestedJarFile, name));
            }
            if (name.endsWith(".class")) {
                try (InputStream in = njar.getInputStream(name)) {
                    clazzCount.addAndGet(1);
                    ProtoServiceData psi = visitProtoService(in, serviceMetaMap, configurationMetaDataMap);
                    if (psi != null) {
                        infoList.add(psi);
                    }
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
        }
        dc.setProtoServiceInfos(infoList);
        if (serviceMetaMap.isEmpty() && configurationMetaDataMap.isEmpty() && infoList.isEmpty()) {
            return null;
        }
        tmpDc = new DeployComponent();
        return dc;
    }

}
