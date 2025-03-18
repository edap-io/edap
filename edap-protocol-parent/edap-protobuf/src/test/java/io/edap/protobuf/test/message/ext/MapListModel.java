package io.edap.protobuf.test.message.ext;

import io.edap.protobuf.test.message.v3.Project;

import java.util.List;
import java.util.Map;

public class MapListModel {

    private long pk;

    private Map<Long, List<Project>> mapList;

    public Map<Long, List<Project>> getMapList() {
        return mapList;
    }

    public void setMapList(Map<Long, List<Project>> mapList) {
        this.mapList = mapList;
    }

    public long getPk() {
        return pk;
    }

    public void setPk(long pk) {
        this.pk = pk;
    }
}
