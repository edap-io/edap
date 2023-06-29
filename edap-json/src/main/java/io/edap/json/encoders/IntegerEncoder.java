package io.edap.json.encoders;

import io.edap.json.JsonEncoder;
import io.edap.json.JsonWriter;

public class IntegerEncoder implements JsonEncoder<Integer> {
    @Override
    public void encode(JsonWriter writer, Integer obj) {
        if (obj == null) {
            writer.writeNull();
        } else {
            writer.write(obj.intValue());
        }
    }
}
