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

import io.edap.log.Logger;
import io.edap.log.LoggerManager;

import java.io.IOException;
import java.nio.channels.SelectableChannel;
import java.nio.channels.SocketChannel;

public class NetUtil {

    static Logger LOG = LoggerManager.getLogger(NetUtil.class);

    private NetUtil() {}

    public static String getRemoteAddress(SelectableChannel channel) {
        if (channel == null || !(channel instanceof SocketChannel)) {
            return "";
        }
        try {
            return ((SocketChannel)channel).getRemoteAddress().toString();
        } catch (IOException e) {
            LOG.warn("channel.getRemoteAddress() error", e);
        }

        return "";
    }
}
