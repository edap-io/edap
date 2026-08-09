package io.edap.container.mw;

import java.util.LinkedHashMap;
import java.util.Map;

public class AnnoData {

    private final String type;                                 // FQCN
    private final Map<String, Object> values = new LinkedHashMap<>();

    public AnnoData(String type) {
        this.type = type;
    }

    public String getType() {
        return type;
    }


    public Map<String, Object> getValues() {
        return values;
    }
}
