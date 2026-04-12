package io.edap.json.encoders;

import io.edap.json.JsonEncoder;
import io.edap.json.JsonWriter;

public class LongEncoder implements JsonEncoder<Long>  {
    @Override
    public void encode(JsonWriter writer, Long obj) {
        if (obj == null) {
            writer.writeNull();
        } else {
            writer.write(obj.longValue());
        }
    }
}
