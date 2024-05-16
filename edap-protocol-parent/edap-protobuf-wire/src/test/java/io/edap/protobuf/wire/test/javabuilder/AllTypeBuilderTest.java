/*
 * Copyright 2022 The edap Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package io.edap.protobuf.wire.test.javabuilder;

import io.edap.protobuf.codegen.JavaGenerator;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

public class AllTypeBuilderTest extends AbstractTest {

    @Test
    public void testAllTypeTest() {
        String workPath = getWorkPath();
        String[] args = new String[]{
                "-proto", workPath + "/resources/proto/all_type.proto",
                "-src", workPath + "/tmpsrc/"

        };
        JavaGenerator.main(args);

        String[] lines = ("package io.edap.protobuf.test.message.v3;\n" +
                "\n" +
                "import io.edap.protobuf.annotation.ProtoField;\n" +
                "import io.edap.protobuf.wire.Field.Type;\n" +
                "import java.io.Serializable;\n" +
                "import java.util.Map;\n" +
                "\n" +
                "/**\n" +
                " * option java_outer_classname = \"MessageOuter\";\n" +
                " */\n" +
                "public class AllType implements Serializable {\n" +
                "\n" +
                "    @ProtoField(tag = 1, type = Type.BOOL)\n" +
                "    private boolean field1;\n" +
                "    @ProtoField(tag = 2, type = Type.BYTES)\n" +
                "    private byte[] field2;\n" +
                "    @ProtoField(tag = 3, type = Type.DOUBLE)\n" +
                "    private double field3;\n" +
                "    @ProtoField(tag = 4, type = Type.MESSAGE)\n" +
                "    private Corpus field4;\n" +
                "    @ProtoField(tag = 5, type = Type.FIXED32)\n" +
                "    private int field5;\n" +
                "    @ProtoField(tag = 6, type = Type.FIXED64)\n" +
                "    private long field6;\n" +
                "    @ProtoField(tag = 7, type = Type.FLOAT)\n" +
                "    private float field7;\n" +
                "    @ProtoField(tag = 8, type = Type.INT32)\n" +
                "    private int field8;\n" +
                "    @ProtoField(tag = 9, type = Type.INT64)\n" +
                "    private long field9;\n" +
                "    @ProtoField(tag = 10, type = Type.MESSAGE)\n" +
                "    private Map<String, Project> field10;\n" +
                "    @ProtoField(tag = 11, type = Type.MESSAGE)\n" +
                "    private Proj field11;\n" +
                "    @ProtoField(tag = 12, type = Type.SFIXED32)\n" +
                "    private int field12;\n" +
                "    @ProtoField(tag = 13, type = Type.SFIXED64)\n" +
                "    private long field13;\n" +
                "    @ProtoField(tag = 14, type = Type.SINT32)\n" +
                "    private int field14;\n" +
                "    @ProtoField(tag = 15, type = Type.SINT64)\n" +
                "    private long field15;\n" +
                "    @ProtoField(tag = 16, type = Type.STRING)\n" +
                "    private String field16;\n" +
                "    @ProtoField(tag = 17, type = Type.UINT32)\n" +
                "    private int field17;\n" +
                "    @ProtoField(tag = 18, type = Type.UINT64)\n" +
                "    private long field18;\n" +
                "\n" +
                "\n" +
                "    public boolean isField1() {\n" +
                "        return field1;\n" +
                "    }\n" +
                "\n" +
                "    public AllType setField1(boolean field1) {\n" +
                "        this.field1 = field1;\n" +
                "        return this;\n" +
                "    }\n" +
                "\n" +
                "    public byte[] getField2() {\n" +
                "        return field2;\n" +
                "    }\n" +
                "\n" +
                "    public AllType setField2(byte[] field2) {\n" +
                "        this.field2 = field2;\n" +
                "        return this;\n" +
                "    }\n" +
                "\n" +
                "    public double getField3() {\n" +
                "        return field3;\n" +
                "    }\n" +
                "\n" +
                "    public AllType setField3(double field3) {\n" +
                "        this.field3 = field3;\n" +
                "        return this;\n" +
                "    }\n" +
                "\n" +
                "    public Corpus getField4() {\n" +
                "        return field4;\n" +
                "    }\n" +
                "\n" +
                "    public AllType setField4(Corpus field4) {\n" +
                "        this.field4 = field4;\n" +
                "        return this;\n" +
                "    }\n" +
                "\n" +
                "    public int getField5() {\n" +
                "        return field5;\n" +
                "    }\n" +
                "\n" +
                "    public AllType setField5(int field5) {\n" +
                "        this.field5 = field5;\n" +
                "        return this;\n" +
                "    }\n" +
                "\n" +
                "    public long getField6() {\n" +
                "        return field6;\n" +
                "    }\n" +
                "\n" +
                "    public AllType setField6(long field6) {\n" +
                "        this.field6 = field6;\n" +
                "        return this;\n" +
                "    }\n" +
                "\n" +
                "    public float getField7() {\n" +
                "        return field7;\n" +
                "    }\n" +
                "\n" +
                "    public AllType setField7(float field7) {\n" +
                "        this.field7 = field7;\n" +
                "        return this;\n" +
                "    }\n" +
                "\n" +
                "    public int getField8() {\n" +
                "        return field8;\n" +
                "    }\n" +
                "\n" +
                "    public AllType setField8(int field8) {\n" +
                "        this.field8 = field8;\n" +
                "        return this;\n" +
                "    }\n" +
                "\n" +
                "    public long getField9() {\n" +
                "        return field9;\n" +
                "    }\n" +
                "\n" +
                "    public AllType setField9(long field9) {\n" +
                "        this.field9 = field9;\n" +
                "        return this;\n" +
                "    }\n" +
                "\n" +
                "    public Map<String, Project> getField10() {\n" +
                "        return field10;\n" +
                "    }\n" +
                "\n" +
                "    public AllType setField10(Map<String, Project> field10) {\n" +
                "        this.field10 = field10;\n" +
                "        return this;\n" +
                "    }\n" +
                "\n" +
                "    public Proj getField11() {\n" +
                "        return field11;\n" +
                "    }\n" +
                "\n" +
                "    public AllType setField11(Proj field11) {\n" +
                "        this.field11 = field11;\n" +
                "        return this;\n" +
                "    }\n" +
                "\n" +
                "    public int getField12() {\n" +
                "        return field12;\n" +
                "    }\n" +
                "\n" +
                "    public AllType setField12(int field12) {\n" +
                "        this.field12 = field12;\n" +
                "        return this;\n" +
                "    }\n" +
                "\n" +
                "    public long getField13() {\n" +
                "        return field13;\n" +
                "    }\n" +
                "\n" +
                "    public AllType setField13(long field13) {\n" +
                "        this.field13 = field13;\n" +
                "        return this;\n" +
                "    }\n" +
                "\n" +
                "    public int getField14() {\n" +
                "        return field14;\n" +
                "    }\n" +
                "\n" +
                "    public AllType setField14(int field14) {\n" +
                "        this.field14 = field14;\n" +
                "        return this;\n" +
                "    }\n" +
                "\n" +
                "    public long getField15() {\n" +
                "        return field15;\n" +
                "    }\n" +
                "\n" +
                "    public AllType setField15(long field15) {\n" +
                "        this.field15 = field15;\n" +
                "        return this;\n" +
                "    }\n" +
                "\n" +
                "    public String getField16() {\n" +
                "        return field16;\n" +
                "    }\n" +
                "\n" +
                "    public AllType setField16(String field16) {\n" +
                "        this.field16 = field16;\n" +
                "        return this;\n" +
                "    }\n" +
                "\n" +
                "    public int getField17() {\n" +
                "        return field17;\n" +
                "    }\n" +
                "\n" +
                "    public AllType setField17(int field17) {\n" +
                "        this.field17 = field17;\n" +
                "        return this;\n" +
                "    }\n" +
                "\n" +
                "    public long getField18() {\n" +
                "        return field18;\n" +
                "    }\n" +
                "\n" +
                "    public AllType setField18(long field18) {\n" +
                "        this.field18 = field18;\n" +
                "        return this;\n" +
                "    }\n" +
                "\n" +
                "}").split("\\n");

        String path = workPath + "/tmpsrc/" + packName + "/AllType.java";
        System.out.println(lines.length);
        assertTrue(codeEquals(path, lines));
    }
}
