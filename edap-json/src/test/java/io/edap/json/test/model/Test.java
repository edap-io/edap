package io.edap.json.test.model;

import io.edap.io.ByteArrayBufOut;
import io.edap.json.Eson;
import io.edap.json.writer.ByteArrayJsonWriter;

import java.io.IOException;
import java.math.BigDecimal;

public class Test {
    public long id;
    public String name;
    public int age;
    public float aFloat;
    public BigDecimal num;

    public static void main(String[] args) throws IOException {
        Test test1 = new Test();
        test1.id = 1L;
        test1.name = "默默";
        test1.age = 26;
        test1.aFloat = 1F;
        test1.num = new BigDecimal("123.2");

        ByteArrayJsonWriter writer = new ByteArrayJsonWriter(new ByteArrayBufOut());
        Eson.serialize(test1,writer);
        writer.toStream(System.out);

    }
}
