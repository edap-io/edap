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

package io.edap.nio;

import io.edap.nio.impl.MethodHandleNetIO;
import io.edap.nio.impl.NativeNetIO;
import io.edap.nio.nativeimpl.FastNetIO;

public class EdapNetIOFactory {

    private static EdapNetIO EDAP_NET_IO;

    public synchronized static EdapNetIO createEdapNetIO() {
        if (EDAP_NET_IO != null) {
            return EDAP_NET_IO;
        }
        boolean enableNative = FastNetIO.isEnableNativeRw();
        System.out.println("arch has NativeImpl =" + enableNative);
        String dEnableNative = System.getProperty("enableNative", "false");
        if (enableNative && "true".equalsIgnoreCase(dEnableNative)) {
            EDAP_NET_IO = new NativeNetIO();
            System.out.println("EDAP_NET_IO=" + EDAP_NET_IO);
        } else {
            EDAP_NET_IO = new MethodHandleNetIO();
            System.out.println("EDAP_NET_IO=" + EDAP_NET_IO);
        }

        return EDAP_NET_IO;
    }
}
