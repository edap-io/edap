package io.edap.container.scan;

import io.edap.container.mw.*;
import io.edap.launcher.NestedJarFile;
import io.edap.protobuf.annotation.ProtoHttp;
import io.edap.protobuf.annotation.ProtoWebSocket;

import java.io.IOException;
import java.util.*;

import static io.edap.container.consts.CoreConstant.WEBSOCKET_DEFAULT_PATH;
import static io.edap.container.utils.JarUtils.scanBuildInfo;
import static io.edap.container.utils.JarUtils.scanMavenInfo;

/**
 * edap微服务ear包的扫描器
 */
public class EarScanner {

    private NestedJarFile earFile;

    public EarScanner(NestedJarFile earFile) {
        this.earFile = earFile;
    }

    public DeployMetaData scanDeployMetaData() throws IOException {
        DeployMetaData dmd = new DeployMetaData();
        NestedJarFile ear = earFile;
        Set<String> names = ear.entryNames();
        List<String> deps = new ArrayList<>();
        for (String name : names) {
            if (name.endsWith("/pom.properties")) {
                dmd.setMavenInfo(scanMavenInfo(ear, name));
            }
            if (name.equals("META-INF/BUILD.json")) {
                dmd.setBuildInfo(scanBuildInfo(ear, name));
            }
            if (name.endsWith(".jar")) {
                deps.add(name);
            }
        }
        Map<String, DeployComponent> componentMap = new HashMap<>();
        Map<String, ProtoMethodData> httpMethodMap = dmd.getProtoHttpMap();
        Map<String, Map<String, ProtoMethodData>> wsMethodMap = dmd.getProtoWebSocketMap();
        for (String name : deps) {
            NestedJarScanner njs = new NestedJarScanner(ear.getNestedJarFile(name));
            DeployComponent dc = njs.scan();
            if (dc != null) {
                componentMap.put(dc.getMavenInfo().getArtifact(), dc);
                filterProtoHttp(httpMethodMap, wsMethodMap, dc.getProtoServiceInfos());
            }
            //System.out.println("dc=" + dc);
        }
        dmd.setComponentMap(componentMap);

        return dmd;
    }

    private void filterProtoHttp(Map<String, ProtoMethodData> protoMethodMap,
                                 Map<String, Map<String, ProtoMethodData>> protoWebSocketMap,
                                 List<ProtoServiceData> protoServices) {
        String protHttpType = ProtoHttp.class.getName();
        String protoWSType  = ProtoWebSocket.class.getName();
        for (ProtoServiceData psd : protoServices) {
            List<ProtoMethodData> ms = psd.getMethodInfos();
            if (ms == null || ms.isEmpty()) {
                continue;
            }

            for (ProtoMethodData pmd : ms) {
                List<AnnoData> annoDatas = pmd.getAnnoDatas();
                for (AnnoData annoData : annoDatas) {
                    if (annoData.getType().equalsIgnoreCase(protHttpType)) {
                        String path = (String)annoData.getValues().get("path");
                        if (path == null) {
                            path = "";
                        }
                        protoMethodMap.put(path, pmd);
                    } else if (annoData.getType().equalsIgnoreCase(protoWSType)) {
                        String path = (String)annoData.getValues().get("path");
                        String method = (String)annoData.getValues().get("method");
                        if (path == null) {
                            path = WEBSOCKET_DEFAULT_PATH;
                        }
                        Map<String, ProtoMethodData> wsDatas = protoWebSocketMap.get(path);
                        if (wsDatas == null) {
                            wsDatas = new HashMap<>();
                            protoWebSocketMap.put(path, wsDatas);
                        }
                        wsDatas.put(method, pmd);
                    }
                }
            }
        }
    }

}
