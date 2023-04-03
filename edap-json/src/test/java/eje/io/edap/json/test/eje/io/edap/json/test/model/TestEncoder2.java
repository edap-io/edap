package eje.io.edap.json.test.eje.io.edap.json.test.model;

import io.edap.json.AbstractEncoder;
import io.edap.json.JsonCodecRegister;
import io.edap.json.JsonEncoder;
import io.edap.json.JsonWriter;
import io.edap.json.test.model.Test;
import io.edap.json.test.model.Test2;

import java.util.Iterator;

public class TestEncoder2 extends AbstractEncoder implements JsonEncoder<Test> {
    private static final byte[] KBS_AFLOAT = ",\"aFloat\":null".getBytes();
    private static final byte[] KBS_AGE = ",\"age\":null".getBytes();
    private static final byte[] KBS_ID = ",\"id\":null".getBytes();
    private static final byte[] KBS_NAME = ",\"name\":null".getBytes();
    private static final byte[] KBS_NUM = ",\"num\":null".getBytes();
    private static final byte[] KBS_ORDERIDS = ",\"orderIds\":null".getBytes();
    private static final byte[] KBS_TEST2LIST = ",\"test2List\":null".getBytes();
    private static final JsonEncoder<Test2> ENCODER_0 = JsonCodecRegister.instance().getEncoder(Test2.class);
    private static final byte[] KBS_TEST2ARRAY = ",\"test2Array\":null".getBytes();
    private static final byte[] KBS_AGEARRAY = ",\"ageArray\":null".getBytes();
    private static final byte[] KBS_FARRAY = ",\"fArray\":null".getBytes();

    public TestEncoder2() {
    }

    public void encode(JsonWriter var1, Test var2) {
        var1.write((byte) 123);
        byte var3 = 1;
        var1.writeField(KBS_AFLOAT, var3, 10);
        var1.write(var2.aFloat);
        var3 = 0;
        var1.writeField(KBS_AGE, var3, 7);
        var1.write(var2.age);
        var1.writeField(KBS_ID, var3, 6);
        var1.write(var2.id);
        if (var2.name != null) {
            var1.writeField(KBS_NAME, var3, 8);
            var1.write(var2.name);
        }

        if (var2.num != null) {
            var1.writeField(KBS_NUM, var3, 7);
            var1.write(var2.num);
        }

        boolean var4;
        Iterator var5;
        if (var2.orderIds != null) {
            var1.writeField(KBS_ORDERIDS, var3, 12);
            var1.write((byte) 91);
            var4 = false;

            Integer var6;
            for (var5 = var2.orderIds.iterator(); var5.hasNext(); var1.write(var6)) {
                var6 = (Integer) var5.next();
                if (var4) {
                    var1.write((byte) 44);
                } else {
                    var4 = true;
                }
            }

            var1.write((byte) 93);
        }

        if (var2.test2List != null) {
            var1.writeField(KBS_TEST2LIST, var3, 13);
            var1.write((byte) 91);
            var4 = false;

            Test2 var8;
            for (var5 = var2.test2List.iterator(); var5.hasNext(); ENCODER_0.encode(var1, var8)) {
                var8 = (Test2) var5.next();
                if (var4) {
                    var1.write((byte) 44);
                } else {
                    var4 = true;
                }
            }

            var1.write((byte) 93);
        }

        int var7;
        if (var2.test2Array != null) {
            var1.writeField(KBS_TEST2ARRAY, var3, 14);
            var1.write((byte) 91);
            var4 = false;

            for (var7 = 0; var7 < var2.test2Array.length; ++var7) {
                if (var4) {
                    var1.write((byte) 44);
                } else {
                    var4 = true;
                }

                ENCODER_0.encode(var1, var2.test2Array[var7]);
            }

            var1.write((byte) 93);
        }

        if (var2.ageArray != null) {
            var1.writeField(KBS_AGEARRAY, var3, 12);
            var1.write((byte) 91);
            var4 = false;

            for (var7 = 0; var7 < var2.ageArray.length; ++var7) {
                if (var4) {
                    var1.write((byte) 44);
                } else {
                    var4 = true;
                }

                var1.write(var2.ageArray[var7]);
            }

            var1.write((byte) 93);
        }

        if (var2.fArray != null) {
            var1.writeField(KBS_FARRAY, var3, 10);
            var1.write((byte) 91);
            var4 = false;

            for (var7 = 0; var7 < var2.fArray.length; ++var7) {
                if (var4) {
                    var1.write((byte) 44);
                } else {
                    var4 = true;
                }

                var1.write(var2.fArray[var7]);
            }

            var1.write((byte) 93);
        }

        var1.write((byte) 125);
    }
}
