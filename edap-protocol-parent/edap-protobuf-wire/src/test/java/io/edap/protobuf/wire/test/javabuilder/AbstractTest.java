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

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.URL;

import static io.edap.protobuf.builder.JavaBuilder.packageToPath;

public abstract class AbstractTest {

    protected String workPath;
    protected String packName = packageToPath("io.edap.protobuf.test.message.v3");

    protected String readUtf8Line(RandomAccessFile javaFile) throws IOException {
        String line = javaFile.readLine();
        if (line == null) {
            return line;
        }
        return new String(line.getBytes("iso-8859-1"), "utf8");
    }

    protected String getWorkPath() {
        if (workPath != null) {
            return workPath;
        }
        URL jarUrl = OneBoolBuilderTest.class.getProtectionDomain().getCodeSource().getLocation();
        File file = new File(jarUrl.getFile());
        String path =file.getParentFile().getParent();
        workPath = path + File.separator + "src" + File.separator + "test";
        return workPath;
    }

    protected boolean codeEquals(String file, String[] lines) {
        try {
            RandomAccessFile javaFile = new RandomAccessFile(file, "r");
            int count = 0;
            String line = readUtf8Line(javaFile);
            while (line != null) {
                if (!line.equals(lines[count])) {
                    System.out.println("[" + line + "]");
                    System.out.println("[" + lines[count] + "]");
                    return false;
                }
                count++;
                line = readUtf8Line(javaFile);
            }
            if (count == lines.length) {
                return true;
            }
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        }
        return false;
    }
}
