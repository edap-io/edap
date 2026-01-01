/*
 * Copyright 2023 The edap Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package io.edap.util;

import java.io.*;

public class FileUtil {

    public static void inputStreamToFile(InputStream in, File file) throws IOException {
        try (FileOutputStream out = new FileOutputStream(file)) {
            byte[] buf = new byte[4096];
            int len = in.read(buf);
            while (len != -1) {
                out.write(buf, 0, len);
                len = in.read(buf);
            }
        }
    }
}
