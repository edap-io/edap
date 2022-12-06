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
import io.edap.protobuf.idl.ProtoIdl;
import io.edap.protobuf.idl.serviceparser.BytecodeParser;
import io.edap.protobuf.idl.util.ClassVisitorUtil;
import io.edap.util.CollectionUtils;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.Set;
import java.util.stream.Stream;

import static io.edap.protobuf.idl.util.ClassVisitorUtil.LOCAL_TYPES;
import static io.edap.protobuf.idl.util.ProtoIdlUtil.writeProtoIdl;

public class BytecodeParseTest {

    @Test
    public void testParseServices() throws IOException {
        String path = "/Users/louis/Downloads/lib";
        File[] jars = new File(path).listFiles();
        long start = System.currentTimeMillis();
        int total = 0;
        for (File f : jars) {
            if (!f.getAbsolutePath().endsWith(".jar")) {
                continue;
            }
            deleteDir("/Users/louis/Downloads/lib/" + f.getName().substring(0, f.getName().length() - 4));
            //System.out.println(f.getAbsolutePath());
            BytecodeParser bytecodeParser = new BytecodeParser(f.getAbsolutePath());
            bytecodeParser.addAnnotationFilter("Lcom/yonyou/cloud/middleware/rpc/RemoteCall;");
            BuildOption buildOption = new BuildOption();
            buildOption.setGrpcCompatible(true);
            ProtoIdl protoIdl = bytecodeParser.parseServices(buildOption);
            if (!CollectionUtils.isEmpty(protoIdl.getServiceProtos())
                    || !CollectionUtils.isEmpty(protoIdl.getDtoProtos())) {
                writeProtoIdl("/Users/louis/Downloads/lib/" + f.getName().substring(0, f.getName().length() - 4), protoIdl);
            }
            total += bytecodeParser.getEntriesCount();
        }
        System.out.println("entriesCount=" + total);
        System.out.println("time=" + (System.currentTimeMillis() - start));
        Set<String> types = ClassVisitorUtil.LOCAL_TYPES.get();
        for (String type : types) {
            //System.out.println(type);
        }

    }

    private static void deleteDir(String dir) throws IOException {
        Path path = Paths.get(dir);
        if (!path.toFile().exists()) {
            return;
        }
        try (Stream<Path> walk = Files.walk(path)) {
            walk.sorted(Comparator.reverseOrder())
                    .forEach(BytecodeParseTest::deleteDirectoryStream);
        }

    }

    private static void deleteDirectoryStream(Path path) {
        try {
            Files.delete(path);
        } catch (IOException e) {
            System.err.printf("无法删除的路径 %s%n%s", path, e);
        }
    }
}
