package io.edap.container.httpadapter;

public class HandlerConfig {

    private ParamConfig[] paramConfig;
    private String        httpMethod;

    public ParamConfig[] getParamConfig() {
        return paramConfig;
    }

    public void setParamConfig(ParamConfig[] paramConfig) {
        this.paramConfig = paramConfig;
    }

    public String getHttpMethod() {
        return httpMethod;
    }

    public void setHttpMethod(String httpMethod) {
        this.httpMethod = httpMethod;
    }
}
