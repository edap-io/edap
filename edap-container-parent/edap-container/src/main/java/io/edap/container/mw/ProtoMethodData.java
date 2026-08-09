package io.edap.container.mw;

import java.util.ArrayList;
import java.util.List;

public class ProtoMethodData {

    private String interfaceName;

    private String name;

    private int access;

    private String paramType;

    private String respType;

    private String sign;

    private String[] exceptions;

    private List<AnnoData> annoDatas;

    public List<AnnoData> getAnnoDatas() {
        if (annoDatas == null) {
            annoDatas = new ArrayList<>();
        }
        return annoDatas;
    }

    public void setAnnoDatas(List<AnnoData> annoDatas) {
        this.annoDatas = annoDatas;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getParamType() {
        return paramType;
    }

    public void setParamType(String paramType) {
        this.paramType = paramType;
    }

    public String getRespType() {
        return respType;
    }

    public void setRespType(String respType) {
        this.respType = respType;
    }

    public String getSign() {
        return sign;
    }

    public void setSign(String sign) {
        this.sign = sign;
    }

    public String[] getExceptions() {
        return exceptions;
    }

    public void setExceptions(String[] exceptions) {
        this.exceptions = exceptions;
    }

    public int getAccess() {
        return access;
    }

    public void setAccess(int access) {
        this.access = access;
    }

    public String getInterfaceName() {
        return interfaceName;
    }

    public void setInterfaceName(String interfaceName) {
        this.interfaceName = interfaceName;
    }
}
