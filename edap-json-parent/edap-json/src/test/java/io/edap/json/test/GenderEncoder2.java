package io.edap.json.test;

import io.edap.json.JsonEncoder;
import io.edap.json.JsonWriter;

public class GenderEncoder2 implements JsonEncoder<Gender> {
    @Override
    public void encode(JsonWriter writer, Gender obj) {
        writer.write(obj.name());
    }
}
