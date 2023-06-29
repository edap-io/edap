package io.edap.json.encoders;

import io.edap.json.JsonEncoder;
import io.edap.json.JsonWriter;

public class StringEncoder implements JsonEncoder<String> {
    @Override
    public void encode(JsonWriter writer, String obj) {
        writer.write(obj);
    }
}
