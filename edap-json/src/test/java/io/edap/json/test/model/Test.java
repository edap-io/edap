package io.edap.json.test.model;

import io.edap.io.ByteArrayBufOut;
import io.edap.json.Eson;
import io.edap.json.writer.ByteArrayJsonWriter;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class Test {
    public long id;
    public String name;
    public int age;
    public float aFloat;
    public BigDecimal num;

    public List<Integer> orderIds;
    public List<Test2> test2List;

    public Test2[] test2Array;
    public int[] ageArray;

    public float[] fArray;

    public static void main(String[] args) throws IOException {
        Test test1 = new Test();
        test1.id = 1L;
        test1.name = "默默";
        test1.age = 26;
        test1.aFloat = 1F;
        test1.num = new BigDecimal("123.2");
        test1.orderIds = Arrays.asList(12,23,34,45);
        test1.fArray = new float[]{1.2f,2.3f,34.5f};


        Test2 test2 = new Test2();
        test2.id = 2L;
        test2.name = "默默2";
        test2.age = 226;
        test2.aFloat = 21F;
        test2.num = new BigDecimal("2123.2");

        Test2 test21 = new Test2();
        test21.id = 21L;
        test21.name = "默默21";
        test21.age = 2126;
        test21.aFloat = 211F;
        test21.num = new BigDecimal("21123.2");

        test1.test2List = Arrays.asList(test2, test21);
        test1.test2Array = new Test2[]{test2, test21};
        test1.ageArray = new int[]{23,34,456};


        ByteArrayJsonWriter writer = new ByteArrayJsonWriter(new ByteArrayBufOut());
        Eson.serialize(test1,writer);
        writer.toStream(System.out);

    }
}
