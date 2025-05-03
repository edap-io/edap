/*
 * Copyright 2023 The edap Project
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

package io.edap.protocol.telnet;

import io.edap.Decoder;
import io.edap.NioServerSession;
import io.edap.Server;
import io.edap.util.StringUtil;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

public class TelnetServer extends Server {

    private static Decoder<TelnetRequest, TelnetServerNioSession> DECODER = new TelnetDecoder();

    private ScheduledExecutorService scheduledExecutorService = Executors.newScheduledThreadPool(5);

    private Map<String, ShellCmdHandler> shellCmdHandlerMap = new ConcurrentHashMap<>();

    public TelnetServer() {
        setDecoder(DECODER);
    }

    @Override
    public NioServerSession createNioSession() {
        TelnetServerNioSession session = new TelnetServerNioSession();
        session.setServer(this);
        session.setDecoder(DECODER);
        return session;
    }

    public void registerShellCmdHandler(String cmd, ShellCmdHandler shellCmdHandler) {
        if (StringUtil.isEmpty(cmd)) {
            throw new RuntimeException("Shell command cann't be empty!");
        }
        if (shellCmdHandler == null) {
            throw new RuntimeException("Shell command handler cann't be empty!");
        }
        ShellCmdHandler old = getShellCmdHandlerMap().putIfAbsent(cmd, shellCmdHandler);
        if (old != null) {
            log.warn("Shell command [{}] be registered!", l -> l.arg(cmd));
        }
    }

    public ScheduledExecutorService getScheduleExecutorService() {
        return scheduledExecutorService;
    }

    public void setScheduleExecutorService(ScheduledExecutorService scheduledExecutorService) {
        this.scheduledExecutorService = scheduledExecutorService;
    }

    public Map<String, ShellCmdHandler> getShellCmdHandlerMap() {
        return shellCmdHandlerMap;
    }
}
