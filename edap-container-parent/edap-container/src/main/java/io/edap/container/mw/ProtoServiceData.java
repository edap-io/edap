package io.edap.container.mw;

import io.edap.protobuf.annotation.ProtoService;

import java.util.ArrayList;
import java.util.List;

public class ProtoServiceData {

    private String typeName;
    private List<AnnoData> annoDatas;
    private List<ProtoMethodData> methodInfos;

    public List<ProtoMethodData> getMethodInfos() {
        return methodInfos;
    }

    public void setMethodInfos(List<ProtoMethodData> methodInfos) {
        this.methodInfos = methodInfos;
    }

    public String getTypeName() {
        return typeName;
    }

    public void setTypeName(String typeName) {
        this.typeName = typeName;
    }

    public List<AnnoData> getAnnoDatas() {
        if (annoDatas == null) {
            annoDatas = new ArrayList<>();
        }
        return annoDatas;
    }

    public void setAnnoDatas(List<AnnoData> annoDatas) {
        this.annoDatas = annoDatas;
    }
}
