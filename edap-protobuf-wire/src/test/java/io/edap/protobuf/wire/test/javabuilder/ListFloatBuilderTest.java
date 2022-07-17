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

public class ListFloatBuilderTest extends AbstractTest {

    @Test
    public void testLisFloatTest() {
        String workPath = getWorkPath();
        String[] args = new String[]{
                "-proto", workPath + "/resources/proto/list_float.proto",
                "-src", workPath + "/tmpsrc/"

        };
        JavaGenerator.main(args);

        String[] lines = ("package io.edap.protobuf.test.message.v3;\n" +
                "\n" +
                "import io.edap.protobuf.annotation.ProtoField;\n" +
                "import io.edap.protobuf.wire.Field.Type;\n" +
                "import java.io.Serializable;\n" +
                "import java.util.ArrayList;\n" +
                "import java.util.List;\n" +
                "\n" +
                "public class ListFloat implements Serializable {\n" +
                "\n" +
                "    @ProtoField(tag = 1, type = Type.FLOAT)\n" +
                "    private List<Float> f;\n" +
                "\n" +
                "\n" +
                "    public List<Float> getF() {\n" +
                "        return f;\n" +
                "    }\n" +
                "\n" +
                "    public ListFloat setF(List<Float> f) {\n" +
                "        this.f = f;\n" +
                "        return this;\n" +
                "    }\n" +
                "\n" +
                "    public void addF(Float itemVar) {\n" +
                "        if (f == null) {\n" +
                "            f = new ArrayList<>();\n" +
                "        }\n" +
                "        f.add(itemVar);\n" +
                "    }\n" +
                "    public void addF(int index, Float itemVar) {\n" +
                "        if (f == null) {\n" +
                "            f = new ArrayList<>();\n" +
                "        }\n" +
                "        f.add(index, itemVar);\n" +
                "    }\n" +
                "    public void addF(List<Float> itemVar) {\n" +
                "        if (f == null) {\n" +
                "            f = new ArrayList<>();\n" +
                "        }\n" +
                "        f.addAll(itemVar);\n" +
                "    }\n" +
                "}").split("\\n");

        String path = workPath + "/tmpsrc/" + packName + "/ListFloat.java";
        System.out.println(lines.length);
        assertTrue(codeEquals(path, lines));
    }
}
