package io.edap.container.ws;

import io.edap.json.JsonObject;

public class BizReqMsg {

    private String id;
    private String method;
    private JsonObject data;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getMethod() {
        return method;
    }

    public void setMethod(String method) {
        this.method = method;
    }

    public JsonObject getData() {
        return data;
    }

    public void setData(JsonObject data) {
        this.data = data;
    }
}
