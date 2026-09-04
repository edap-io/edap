package io.edap.json.test;

import io.edap.json.JsonEncoder;
import io.edap.json.JsonWriter;

import java.nio.charset.StandardCharsets;

public class GenderEncoder implements JsonEncoder<Gender> {

    static byte[][] values;
    static {
        values[0] = "male".getBytes(StandardCharsets.UTF_8);
        values[1] = "female".getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public void encode(JsonWriter writer, Gender obj) {
        writer.write(values[obj.ordinal()]);
    }
}
