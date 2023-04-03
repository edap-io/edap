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

import static io.edap.log.helpers.Util.printError;

public class ZipCompression implements LogCompression {

    private static final int BUFFER_SIZE = 8192;

    @Override
    public String getSuffix() {
        return "zip";
    }

    @Override
    public void compress(String plainName, String gzName) {
        File file2gz = new File(plainName);
        if (!file2gz.exists()) {
            throw new RuntimeException("[" + plainName + "] not founc");
        }

        String innerEntryName;
        int lastSlash = gzName.lastIndexOf('/');
        if (lastSlash == -1) {
            innerEntryName = gzName;
        } else {
            innerEntryName = gzName.substring(lastSlash+1);
        }
        if (!gzName.endsWith(".zip")) {
            gzName = gzName + ".zip";
        } else {
            innerEntryName = innerEntryName.substring(0, innerEntryName.length()-4);
        }

        File gzedFile = new File(gzName);

        if (gzedFile.exists()) {
            throw new RuntimeException("The target compressed file named [" + gzName
                    + "] exist already. Aborting file compression.");
        }

        try (BufferedInputStream bis = new BufferedInputStream(new FileInputStream(plainName));
             ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(gzedFile))) {

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
