package io.edap.json.encoders;

import io.edap.json.JsonEncoder;
import io.edap.json.JsonWriter;

public class BooleanEncoder implements JsonEncoder<Boolean>  {

    @Override
    public void encode(JsonWriter writer, Boolean obj) {
        if (obj == null) {
            writer.writeNull();
        } else {
            writer.write(obj.booleanValue());
        }
    }
}
