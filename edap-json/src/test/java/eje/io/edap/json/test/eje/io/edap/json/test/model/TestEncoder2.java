package eje.io.edap.json.test.eje.io.edap.json.test.model;

import io.edap.json.AbstractEncoder;
import io.edap.json.JsonCodecRegister;
import io.edap.json.JsonEncoder;
import io.edap.json.JsonWriter;
import io.edap.json.test.model.Test;
import io.edap.json.test.model.Test2;

public class TestEncoder2 extends AbstractEncoder implements JsonEncoder<Test> {
    private static final byte[] KBS_AFLOAT = ",\"aFloat\":null".getBytes();
    private static final byte[] KBS_AGE = ",\"age\":null".getBytes();
    private static final byte[] KBS_ID = ",\"id\":null".getBytes();
    private static final byte[] KBS_NAME = ",\"name\":null".getBytes();
    private static final byte[] KBS_NUM = ",\"num\":null".getBytes();
    private static final byte[] KBS_TEST2LIST = ",\"test2List\":null".getBytes();
    private static final JsonEncoder<Test2> ENCODER_0 = JsonCodecRegister.instance().getEncoder(Test2.class);

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
        if (var2.orderIds != null) {
            boolean hasWrite = false;
            for (Integer oid : var2.orderIds) {
                if (hasWrite) {
                    var1.write(',');
                } else {
                    hasWrite = true;
                }
                var1.write(oid);
            }
        }

        if (var2.test2List != null) {
            var1.writeField(KBS_TEST2LIST, var3, 13);
            var1.write('[');
            boolean hasWrite = false;
            for (Test2 t : var2.test2List) {
                if (hasWrite) {
                    var1.write(',');
                } else {
                    hasWrite = true;
                }
                ENCODER_0.encode(var1, t);
            }
            var1.write(']');
        }

        if (var2.test2Array != null) {
            var1.writeField(KBS_TEST2LIST, var3, 13);
            var1.write('[');
            boolean hasWrite = false;
            for (int i=0;i<var2.test2Array.length;i++) {
                if (hasWrite) {
                    var1.write(',');
                } else {
                    hasWrite = true;
                }
                ENCODER_0.encode(var1, var2.test2Array[i]);
            }
            var1.write(']');
        }
        if (var2.ageArray != null) {
            var1.writeField(KBS_TEST2LIST, var3, 13);
            var1.write('[');
            boolean hasWrite = false;
            for (int i=0;i<var2.ageArray.length;i++) {
                if (hasWrite) {
                    var1.write(',');
                } else {
                    hasWrite = true;
                }
                var1.write(var2.ageArray[i]);
            }
            var1.write(']');
        }
        var1.write('}');
    }
}
