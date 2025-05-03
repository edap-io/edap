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

import io.edap.NioServerSession;
import io.edap.buffer.FastBuf;
import io.edap.log.Logger;
import io.edap.log.LoggerManager;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Map;
import java.util.concurrent.*;

public class TelnetServerNioSession extends NioServerSession {

    static Logger LOG = LoggerManager.getLogger(TelnetServerNioSession.class);

    private FastBuf buf;

    private ScheduledFuture<?> scheduledFuture;
    private volatile boolean taskRunning = false;
    private ScheduledExecutorService scheduledExecutorService;
    private Map<String, ShellCmdHandler> shellCmdHandlerMap;

    @Override
    public void handle(Object message) {
        LOG.info(message);
        if (message instanceof IACCommand) {
            IACCommand iacCommand = (IACCommand)message;
            if (iacCommand.getCommand() == IAC.IP) {
                interruptProcess();
            }
        } else if (message instanceof ShellCommand) {
            ShellCommand command = (ShellCommand) message;
            if (shellCmdHandlerMap == null) {
                shellCmdHandlerMap = ((TelnetServer)getServer()).getShellCmdHandlerMap();
            }
            ShellCmdHandler handler = shellCmdHandlerMap.get(command.getCommand());
            if (handler == null) {
                writeString("Cann't found command [" + command.getCommand() + "]\r\n");
            } else {
                handler.process(command, this);
            }
        }
    }

    private void interruptProcess() {
        if (scheduledFuture != null) {
            scheduledFuture.cancel(true);
        }
        if (taskRunning) {
            taskRunning = false;
        }
        writeBytes((byte)255, (byte)252, (byte)6);
        writeString("\n-> ");
    }

    public void writeBytes(byte... bs) {
        if (buf == null) {
            buf = new FastBuf(4096);
        }
        buf.reset();
        buf.write(bs, 0, bs.length);
        try {
            fastWrite(buf);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public void writeString(String data) {
        if (buf == null) {
            buf = new FastBuf(4096);
        }
        buf.reset();
        byte[] bs = data.getBytes(StandardCharsets.UTF_8);
        buf.write(bs, 0, bs.length);
        try {
            fastWrite(buf);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
