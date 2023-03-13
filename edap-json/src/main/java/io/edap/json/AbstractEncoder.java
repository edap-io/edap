package io.edap.json;

import java.util.Map;

public abstract class AbstractEncoder {

    public boolean writeEmptyMap(JsonWriter writer, Map map) {
        if (map == null) {
            writer.writeNull();
            return true;
        }
        if (map.isEmpty()) {
            writer.write((byte)'{', (byte)'}');
            return true;
        }
        return false;
    }
}
