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

package io.edap.protobuf.idl.test;

import io.edap.protobuf.idl.BuildOption;
import io.edap.protobuf.idl.serviceparser.BytecodeParser;
import org.junit.jupiter.api.Test;

import java.io.File;

public class BytecodeParseTest {

    @Test
    public void testParseServices() {
        String path = "/Users/louis/IdeaProjects/yts-demo-pay/yts-demo-pay-be/dev-yts-demo-pay-bootstrap/target/mdd/WEB-INF/lib";
        File[] jars = new File(path).listFiles();
        for (File f : jars) {
            if (!f.getAbsolutePath().endsWith(".jar")) {
                continue;
            }
            BytecodeParser bytecodeParser = new BytecodeParser(f.getAbsolutePath());
            bytecodeParser.parseServices(new BuildOption());
        }
    }
}
