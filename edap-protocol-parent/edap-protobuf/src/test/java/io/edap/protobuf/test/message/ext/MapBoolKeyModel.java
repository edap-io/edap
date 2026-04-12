package io.edap.protobuf.test.message.ext;

import io.edap.protobuf.test.message.v3.Project;

import java.util.Map;

public class MapBoolKeyModel {

    private long pk;

    private Map<Double,   Project> boolKey;

    public Map<Double, Project> getBoolKey() {
        return boolKey;
    }

    public void setBoolKey(Map<Double, Project> boolKey) {
        this.boolKey = boolKey;
    }

    public long getPk() {
        return pk;
    }

    public void setPk(long pk) {
        this.pk = pk;
    }
}
