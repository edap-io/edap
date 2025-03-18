package io.edap.protobuf.test.message.ext;

import io.edap.protobuf.test.message.v3.Project;

import java.util.Map;

public class MapBoolValModel {

    private long pk;

    private Map<String,   Byte> boolKey;

    public Map<String, Byte> getBoolKey() {
        return boolKey;
    }

    public void setBoolKey(Map<String, Byte> boolKey) {
        this.boolKey = boolKey;
    }

    public long getPk() {
        return pk;
    }

    public void setPk(long pk) {
        this.pk = pk;
    }
}
