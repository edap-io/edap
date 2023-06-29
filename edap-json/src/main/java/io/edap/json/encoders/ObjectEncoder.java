package io.edap.json.encoders;

import io.edap.json.Eson;
import io.edap.json.JsonEncoder;
import io.edap.json.JsonWriter;

public class ObjectEncoder implements JsonEncoder<Object> {
    @Override
    public void encode(JsonWriter writer, Object obj) {
        if (obj == null) {
            writer.writeNull();
        } else {
            Eson.toJsonString(obj);
        }
    }
}
