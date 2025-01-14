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

public class OneBoolBuilderTest extends AbstractTest {

    @Test
    public void testOneBool() {
        String workPath = getWorkPath();
        String[] args = new String[]{
                "-proto", workPath + "/resources/proto/one_bool.proto",
                "-src", workPath + "/tmpsrc/"

        };
        JavaGenerator.main(args);

        String[] lines = ("package io.edap.protobuf.test.message.v3;\n" +
                "\n" +
                "import io.edap.protobuf.annotation.ProtoField;\n" +
                "import io.edap.protobuf.wire.Field.Type;\n" +
                "import java.io.Serializable;\n" +
                "\n" +
                "public class OneBool implements Serializable {\n" +
                "\n" +
                "    @ProtoField(tag = 1, type = Type.BOOL)\n" +
                "    private boolean value;\n" +
                "\n" +
                "\n" +
                "    public boolean isValue() {\n" +
                "        return value;\n" +
                "    }\n" +
                "\n" +
                "    public OneBool setValue(boolean value) {\n" +
                "        this.value = value;\n" +
                "        return this;\n" +
                "    }\n" +
                "\n" +
                "}").split("\\n");
        String path = workPath + "/tmpsrc/" + packName + "/OneBool.java";
        System.out.println(lines.length);
        assertTrue(codeEquals(path, lines));
    }

    @Test
    public void testOneBoolBoxedDefaultVal() {
        String workPath = getWorkPath();
        String[] args = new String[]{
                "-proto", workPath + "/resources/proto/one_bool.proto",
                "-src", workPath + "/tmpsrc/",
                "-useBoxed", "true",
                "-hasDefaultValue", "true"

        };
        JavaGenerator.main(args);

        String[] lines = ("package io.edap.protobuf.test.message.v3;\n" +
                "\n" +
                "import io.edap.protobuf.annotation.ProtoField;\n" +
                "import io.edap.protobuf.wire.Field.Type;\n" +
                "import java.io.Serializable;\n" +
                "\n" +
                "public class OneBool implements Serializable {\n" +
                "\n" +
                "    @ProtoField(tag = 1, type = Type.BOOL)\n" +
                "    private Boolean value;\n" +
                "\n" +
                "    public OneBool() {\n" +
                "        this.value = false;\n" +
                "    }\n" +
                "\n" +
                "    public Boolean isValue() {\n" +
                "        return value;\n" +
                "    }\n" +
                "\n" +
                "    public OneBool setValue(Boolean value) {\n" +
                "        this.value = value;\n" +
                "        return this;\n" +
                "    }\n" +
                "\n" +
                "}").split("\\n");
        String path = workPath + "/tmpsrc/" + packName + "/OneBool.java";
        System.out.println(lines.length);
        assertTrue(codeEquals(path, lines));
    }

    @Test
    public void testOneBoolBoxed() {
        String workPath = getWorkPath();
        String[] args = new String[]{
                "-proto", workPath + "/resources/proto/one_bool.proto",
                "-src", workPath + "/tmpsrc/",
                "-useBoxed", "true"

        };
        JavaGenerator.main(args);

        String[] lines = ("package io.edap.protobuf.test.message.v3;\n" +
                "\n" +
                "import io.edap.protobuf.annotation.ProtoField;\n" +
                "import io.edap.protobuf.wire.Field.Type;\n" +
                "import java.io.Serializable;\n" +
                "\n" +
                "public class OneBool implements Serializable {\n" +
                "\n" +
                "    @ProtoField(tag = 1, type = Type.BOOL)\n" +
                "    private Boolean value;\n" +
                "\n" +
                "\n" +
                "    public Boolean isValue() {\n" +
                "        return value;\n" +
                "    }\n" +
                "\n" +
                "    public OneBool setValue(Boolean value) {\n" +
                "        this.value = value;\n" +
                "        return this;\n" +
                "    }\n" +
                "\n" +
                "}").split("\\n");
        String path = workPath + "/tmpsrc/" + packName + "/OneBool.java";
        System.out.println(lines.length);
        assertTrue(codeEquals(path, lines));
    }

    @Test
    public void testOneBoolFieldComment1() {
        String workPath = getWorkPath();
        String[] args = new String[]{
                "-proto", workPath + "/resources/proto/one_bool_field_comment1.proto",
                "-src", workPath + "/tmpsrc/"

        };
        JavaGenerator.main(args);

        String[] lines = ("package io.edap.protobuf.test.message.v3;\n" +
                "\n" +
                "import io.edap.protobuf.annotation.ProtoField;\n" +
                "import io.edap.protobuf.wire.Field.Type;\n" +
                "import java.io.Serializable;\n" +
                "\n" +
                "public class OneBoolFieldComment1 implements Serializable {\n" +
                "\n" +
                "    /**\n" +
                "     * 第一行注释\n" +
                "     */\n" +
                "    @ProtoField(tag = 1, type = Type.BOOL)\n" +
                "    private boolean value;\n" +
                "\n" +
                "\n" +
                "    public boolean isValue() {\n" +
                "        return value;\n" +
                "    }\n" +
                "\n" +
                "    public OneBoolFieldComment1 setValue(boolean value) {\n" +
                "        this.value = value;\n" +
                "        return this;\n" +
                "    }\n" +
                "\n" +
                "}").split("\\n");
        String path = workPath + "/tmpsrc/" + packName + "/OneBoolFieldComment1.java";
        System.out.println(lines.length);
        assertTrue(codeEquals(path, lines));
    }

    @Test
    public void testOneBoolFieldComment2() {
        String workPath = getWorkPath();
        String[] args = new String[]{
                "-proto", workPath + "/resources/proto/one_bool_field_comment2.proto",
                "-src", workPath + "/tmpsrc/"

        };
        JavaGenerator.main(args);

        String[] lines = ("package io.edap.protobuf.test.message.v3;\n" +
                "\n" +
                "import io.edap.protobuf.annotation.ProtoField;\n" +
                "import io.edap.protobuf.wire.Field.Type;\n" +
                "import java.io.Serializable;\n" +
                "\n" +
                "public class OneBoolFieldComment2 implements Serializable {\n" +
                "\n" +
                "    /**\n" +
                "     * 第一行注释\n" +
                "     * 第二行注释\n" +
                "     */\n" +
                "    @ProtoField(tag = 1, type = Type.BOOL)\n" +
                "    private boolean value;\n" +
                "\n" +
                "\n" +
                "    public boolean isValue() {\n" +
                "        return value;\n" +
                "    }\n" +
                "\n" +
                "    public OneBoolFieldComment2 setValue(boolean value) {\n" +
                "        this.value = value;\n" +
                "        return this;\n" +
                "    }\n" +
                "\n" +
                "}").split("\\n");
        String path = workPath + "/tmpsrc/" + packName + "/OneBoolFieldComment2.java";
        System.out.println(lines.length);
        assertTrue(codeEquals(path, lines));
    }
}
