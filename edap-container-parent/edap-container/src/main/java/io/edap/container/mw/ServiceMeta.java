package io.edap.container.mw;

import java.util.List;

public class ServiceMeta {

    private String className;
    private String superName;
    private List<String> interfaceList;
    private AnnoData annoData;

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

    public AnnoData getAnnoData() {
        return annoData;
    }

    public void setAnnoData(AnnoData annoData) {
        this.annoData = annoData;
    }

    public String getSuperName() {
        return superName;
    }

    public void setSuperName(String superName) {
        this.superName = superName;
    }
}
