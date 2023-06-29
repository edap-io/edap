package io.edap.json.encoders;

import io.edap.json.JsonEncoder;
import io.edap.json.JsonWriter;

public class DoubleEncoder implements JsonEncoder<Double> {
    @Override
    public void encode(JsonWriter writer, Double obj) {
        if (obj == null) {
            writer.writeNull();
        } else {
            writer.write(obj.doubleValue());
        }
    }
}
