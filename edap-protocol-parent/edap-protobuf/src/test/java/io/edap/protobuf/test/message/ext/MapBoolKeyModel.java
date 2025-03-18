package io.edap.protobuf.test.message.ext;

import io.edap.protobuf.test.message.v3.Project;

import java.util.Map;

public class MapBoolKeyModel {

    private long pk;

    private Map<Long,   Project> boolKey;

    public Map<Long, Project> getBoolKey() {
        return boolKey;
    }

    public void setBoolKey(Map<Long, Project> boolKey) {
        this.boolKey = boolKey;
    }

    public long getPk() {
        return pk;
    }

    public void setPk(long pk) {
        this.pk = pk;
    }
}
