package io.edap.container.mw;

import io.edap.launcher.NestedJarFile;

import java.io.File;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class DeployMetaData {

    private File orignalFile;
    private MavenInfo mavenInfo;
    private BuildInfo buildInfo;
    private Map<String, DeployComponent> componentMap = new ConcurrentHashMap<>();
    private Map<String, ProtoMethodData> protoHttpMap = new ConcurrentHashMap<>();
    private Map<String, ServiceMeta> serviceMetaMap   = new ConcurrentHashMap<>();
    private Map<String, Map<String, ProtoMethodData>> protoWebSocketMap = new ConcurrentHashMap<>();

    public MavenInfo getMavenInfo() {
        return mavenInfo;
    }

    public void setMavenInfo(MavenInfo mavenInfo) {
        this.mavenInfo = mavenInfo;
    }

    public Map<String, DeployComponent> getComponentMap() {
        return componentMap;
    }

    public void setComponentMap(Map<String, DeployComponent> componentMap) {
        this.componentMap = componentMap;
    }

    public Map<String, ProtoMethodData> getProtoHttpMap() {
        return protoHttpMap;
    }

    public void setProtoHttpMap(Map<String, ProtoMethodData> protoHttpMap) {
        this.protoHttpMap = protoHttpMap;
    }

    public Map<String, Map<String, ProtoMethodData>> getProtoWebSocketMap() {
        return protoWebSocketMap;
    }

    public void setProtoWebSocketMap(Map<String, Map<String, ProtoMethodData>> protoWebSocketMap) {
        this.protoWebSocketMap = protoWebSocketMap;
    }

    public BuildInfo getBuildInfo() {
        return buildInfo;
    }

    public void setBuildInfo(BuildInfo buildInfo) {
        this.buildInfo = buildInfo;
    }

    public Map<String, ServiceMeta> getServiceMetaMap() {
        return serviceMetaMap;
    }

    public void setServiceMetaMap(Map<String, ServiceMeta> serviceMetaMap) {
        this.serviceMetaMap = serviceMetaMap;
    }

    public File getOrignalFile() {
        return orignalFile;
    }

    public void setOrignalFile(File orignalFile) {
        this.orignalFile = orignalFile;
    }
}
