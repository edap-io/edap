//
// Source code recreated from a .class file by IntelliJ IDEA
// (powered by FernFlower decompiler)
//

package eje.com.jsoniter.benchmark.with_map_field;

import io.edap.json.*;
import io.edap.json.enums.DataType;
import io.edap.json.test.model.DemoOneString;
import io.edap.util.ClazzUtil;
import java.lang.reflect.Field;

import static io.edap.json.Eson.serialize;
import static io.edap.util.AsmUtil.getFieldType;

public class TestObjectEncoder extends AbstractEncoder implements JsonEncoder<TestObject> {
    private static final byte[] KBS_FIELD1 = ",\"field1\":null".getBytes();

    private MapEncoder<String, DemoOneString> valueEncoder;

    public TestObjectEncoder() {
        valueEncoder = JsonCodecRegister.instance().getMapEncoder(getFieldType(TestObject.class, "map"),
                TestObject.class, DataType.BYTE_ARRAY);
    }

    static {
        try {
            Field var2 = ClazzUtil.getField(TestObject.class, "methods");
        } catch (NoSuchFieldException var3) {
            var3.printStackTrace();
        }
    }

    public void encode(JsonWriter param1, TestObject param2) {
        if (param2.map != null) {
            valueEncoder.encode(param1, param2.map);
        }
    }
}
