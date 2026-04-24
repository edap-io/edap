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

import io.edap.log.Logger;
import io.edap.log.LoggerManager;
import io.edap.nio.impl.MethodHandleNetIO;
import io.edap.nio.impl.NativeNetIO;
import io.edap.nio.nativeimpl.FastNetIO;

public class EdapNetIOFactory {

    private static final Logger LOG = LoggerManager.getLogger(EdapNetIOFactory.class);

    private static EdapNetIO EDAP_NET_IO;

    public synchronized static EdapNetIO createEdapNetIO() {
        if (EDAP_NET_IO != null) {
            return EDAP_NET_IO;
        }
        boolean enableNative = FastNetIO.isEnableNativeRw();
        LOG.info("arch has NativeImpl {}", l -> l.arg(enableNative));
        String dEnableNative = System.getProperty("enableNative", "false");
        if (enableNative && "true".equalsIgnoreCase(dEnableNative)) {
            EDAP_NET_IO = new NativeNetIO();
            LOG.info("EDAP_NET_IO {}", l -> l.arg(EDAP_NET_IO));
        } else {
            EDAP_NET_IO = new MethodHandleNetIO();
            LOG.info("EDAP_NET_IO {}", l -> l.arg(EDAP_NET_IO));
        }

        return EDAP_NET_IO;
    }
}
