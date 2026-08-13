package io.edap.container.mw;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class DeployMetaData {
    /**
     * 原始的ear的文件
     */
    private File orignalFile;
    /**
     * ear应用包的maven相关信息
     */
    private MavenInfo mavenInfo;
    /**
     * ear应用包的构建相关信息
     */
    private BuildInfo buildInfo;
    /**
     * 应用包内接口以及实现相关的元数据
     */
    private Map<String, DeployComponent> componentMap = new ConcurrentHashMap<>();

    /**
     * 添加@ProtoService注解的接口，通常是由edap的插件根据proto文件生成的java接口
     */
    private List<ProtoServiceData> protoServiceInfos = new ArrayList<>();
    /**
     * 容器管理的Bean，被其他bean依赖注入的，通常是添加@MicroServiceBean(Edap添加@ProtoService注解的接口实现)
     * 和 @Bean 注解的Bean
     */
    private Map<String, ServiceMeta> serviceMetaMap = new HashMap<>();

    private Map<String, ConfigurationMetaData> configurationMetaMap = new HashMap<>();

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

    public BuildInfo getBuildInfo() {
        return buildInfo;
    }

    public void setBuildInfo(BuildInfo buildInfo) {
        this.buildInfo = buildInfo;
    }

    public File getOrignalFile() {
        return orignalFile;
    }

    public void setOrignalFile(File orignalFile) {
        this.orignalFile = orignalFile;
    }

    /**
     * 添加@ProtoService注解的接口，通常是由edap的插件根据proto文件生成的java接口
     */
    public List<ProtoServiceData> getProtoServiceInfos() {
        return protoServiceInfos;
    }

    public void setProtoServiceInfos(List<ProtoServiceData> protoServiceInfos) {
        this.protoServiceInfos = protoServiceInfos;
    }

    /**
     * 容器管理的Bean，被其他bean依赖注入的，通常是添加@MicroServiceBean(Edap添加@ProtoService注解的接口实现)
     * 和 @Bean 注解的Bean
     */
    public Map<String, ServiceMeta> getServiceMetaMap() {
        return serviceMetaMap;
    }

    public void setServiceMetaMap(Map<String, ServiceMeta> serviceMetaMap) {
        this.serviceMetaMap = serviceMetaMap;
    }

    public Map<String, ConfigurationMetaData> getConfigurationMetaMap() {
        return configurationMetaMap;
    }

    public void setConfigurationMetaMap(Map<String, ConfigurationMetaData> configurationMetaMap) {
        this.configurationMetaMap = configurationMetaMap;
    }
}
