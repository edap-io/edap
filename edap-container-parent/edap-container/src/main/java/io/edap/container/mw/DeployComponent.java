package io.edap.container.mw;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * ear应用包内的jar包元数据
 */
public class DeployComponent {

    /**
     * java包maven相关的信息
     */
    private MavenInfo mavenInfo;
    /**
     * 添加@ProtoService注解的接口，通常是由edap的插件根据proto文件生成的java接口
     */
    private List<ProtoServiceData> protoServiceInfos;
    /**
     * 容器管理的Bean，被其他bean依赖注入的，通常是添加@MicroServiceBean(Edap添加@ProtoService注解的接口实现)
     * 和 @Bean 注解的Bean
     */
    private Map<String, ServiceMeta> serviceMetaMap = new HashMap<>();

    private Map<String, ConfigurationMetaData> configurationMetaMap = new HashMap<>();

    public List<ProtoServiceData> getProtoServiceInfos() {
        return protoServiceInfos;
    }

    public void setProtoServiceInfos(List<ProtoServiceData> protoServiceInfos) {
        this.protoServiceInfos = protoServiceInfos;
    }

    public MavenInfo getMavenInfo() {
        return mavenInfo;
    }

    public void setMavenInfo(MavenInfo mavenInfo) {
        this.mavenInfo = mavenInfo;
    }

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
