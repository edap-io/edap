package io.edap.container.httpadapter;

import io.edap.microservice.enums.ParamType;

public class ParamConfig {

    private String    paramName;
    private ParamType paramType;

    public String getParamName() {
        return paramName;
    }

    public void setParamName(String paramName) {
        this.paramName = paramName;
    }

    public ParamType getParamType() {
        return paramType;
    }

    public void setParamType(ParamType paramType) {
        this.paramType = paramType;
    }
}
