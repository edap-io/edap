package io.edap.container.mw;

import io.edap.container.BeanDef;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class DeployComponent {

    private MavenInfo mavenInfo;

    private List<ProtoServiceData> protoServiceInfos;

    private Map<String, ServiceMeta> serviceMetaMap = new HashMap<>();

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

}
