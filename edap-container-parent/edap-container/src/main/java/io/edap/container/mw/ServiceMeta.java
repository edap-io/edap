package io.edap.container.mw;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ServiceMeta {

    private String className;
    private String superName;
    private List<String> interfaceList;
    private Map<String, AnnoData> annoDatas;

    public String getClassName() {
        return className;
    }

    public void setClassName(String className) {
        this.className = className;
    }

    public List<String> getInterfaceList() {
        return interfaceList;
    }

    public void setInterfaceList(List<String> interfaceList) {
        this.interfaceList = interfaceList;
    }

    public Map<String, AnnoData> getAnnoDatas() {
        return annoDatas;
    }

    public void putAnnoData(String name, AnnoData annoData) {
        if (annoDatas == null) {
            annoDatas = new HashMap<>();
        }
        annoDatas.put(name, annoData);
    }

    public void setAnnoData(Map<String, AnnoData> annoDatas) {
        this.annoDatas = annoDatas;
    }

    public String getSuperName() {
        return superName;
    }

    public void setSuperName(String superName) {
        this.superName = superName;
    }
}
