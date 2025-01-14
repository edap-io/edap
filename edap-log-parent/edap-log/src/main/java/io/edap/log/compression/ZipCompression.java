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

package io.edap.log.compression;

import io.edap.log.LogCompression;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class ZipCompression implements LogCompression {

    private static final int BUFFER_SIZE = 8192;

    @Override
    public String getSuffix() {
        return "zip";
    }

    @Override
    public void compress(File file2gz, File gzFile) {
        if (!file2gz.exists()) {
            throw new RuntimeException("[" + file2gz.getAbsolutePath() + "] not found");
        }

        String innerEntryName;
        String gzName = gzFile.getAbsolutePath();
        int lastSlash = gzName.lastIndexOf('/');
        if (lastSlash == -1) {
            innerEntryName = gzName;
        } else {
            innerEntryName = gzName.substring(lastSlash+1);
        }
        if (gzName.endsWith(".zip")) {
            innerEntryName = innerEntryName.substring(0, innerEntryName.length()-4);
        }

        if (gzFile.exists()) {
            throw new RuntimeException("The target compressed file named [" + gzName
                    + "] exist already. Aborting file compression.");
        }

        try (BufferedInputStream bis = new BufferedInputStream(new FileInputStream(file2gz));
             ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(gzFile))) {

            ZipEntry zipEntry = new ZipEntry(innerEntryName);
            zos.putNextEntry(zipEntry);

            byte[] inbuf = new byte[BUFFER_SIZE];
            int n;

            while ((n = bis.read(inbuf)) != -1) {
                zos.write(inbuf, 0, n);
            }

        } catch (Exception e) {
            throw new RuntimeException("ZipCompression compress error", e);
        }
    }
}
