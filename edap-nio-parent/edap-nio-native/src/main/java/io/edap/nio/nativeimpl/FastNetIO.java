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

package io.edap.nio.nativeimpl;

import java.io.File;
import java.io.FileDescriptor;
import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;
import java.util.Properties;
import java.util.UUID;

import static io.edap.util.FileUtil.inputStreamToFile;

public class FastNetIO {

    static boolean ENABLE_NATIVE_RW;

    static {
        Properties props = System.getProperties();
        String archKey = "os.arch";
        String osKey   = "os.name";
        String os      = (String)props.get(osKey);
        if (os == null) {
            os = "";
        } else {
            os = os.toUpperCase(Locale.ENGLISH);
        }
        String arch = (String)props.get(archKey);

        if (arch == null) {
            arch = "";
        } else {
            arch = arch.toLowerCase(Locale.ENGLISH);
        }
        if (os.toUpperCase(Locale.ENGLISH).indexOf("MAC") != -1) {
            os = "macos";
        } else if (os.toUpperCase(Locale.ENGLISH).indexOf("SunOS") != -1) {
            os = "SunOS";
        } else {
            os = "linux";
            if (arch.equals("amd64")) {
                arch = "x86_64";
            }
        }
        String libPath = "/edap-nio-native-" + os + "_" + arch + ".o";

        try (InputStream in = FastNetIO.class.getResourceAsStream(libPath)) {
            File nativeTmpFile = File.createTempFile(UUID.randomUUID().toString(), ".o");
            inputStreamToFile(in, nativeTmpFile);
            System.load(nativeTmpFile.getAbsolutePath());
            nativeTmpFile.delete();
            initIDs();
            ENABLE_NATIVE_RW = true;
        } catch (Throwable e) {
            e.printStackTrace();
            ENABLE_NATIVE_RW = false;
        }
    }

    public static boolean isEnableNativeRw() {
        return ENABLE_NATIVE_RW;
    }

    public static int read(FileDescriptor fd, long address, int len) throws IOException {
        return read0(fd, address, len);
    }

    public static int write(FileDescriptor fd, long address, int len) throws IOException {
        return write0(fd, address, len);
    }

    static native int read0(FileDescriptor fd, long address, int len)
            throws IOException;

    static native int write0(FileDescriptor fd, long address, int len)
            throws IOException;

    static native void initIDs();
}
