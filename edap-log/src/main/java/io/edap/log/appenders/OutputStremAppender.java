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

package io.edap.log.appenders;

import io.edap.log.Appender;
import io.edap.log.Encoder;
import io.edap.log.LogEvent;
import io.edap.log.LogOutputStream;
import io.edap.log.helps.ByteArrayBuilder;
import io.edap.log.io.BaseLogOutputStream;

import java.io.IOException;
import java.util.concurrent.locks.ReentrantLock;

public class OutputStremAppender implements Appender {

    protected final ReentrantLock lock = new ReentrantLock(false);

    private Encoder encoder;

    private BaseLogOutputStream outputStream;

    private boolean immediateFlush;

    private String name;

    private boolean started;

    @Override
    public void append(LogEvent logEvent) throws IOException {
        if (encoder == null) {
            return;
        }
        ByteArrayBuilder builder = encoder.encode(logEvent);
        lock.lock();
        try {
            builder.writeToLogOut(outputStream);
            if (isImmediateFlush()) {
                this.outputStream.flush();
            }
        } finally {
            lock.unlock();
        }
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public void setName(String name) {
        this.name = name;
    }

    @Override
    public LogOutputStream getLogoutStream() {
        return outputStream;
    }

    public ReentrantLock getLock() {
        return lock;
    }

    public Encoder getEncoder() {
        return encoder;
    }

    public void setEncoder(Encoder encoder) {
        this.encoder = encoder;
    }

    public BaseLogOutputStream getOutputStream() {
        return outputStream;
    }

    public void setOutputStream(BaseLogOutputStream outputStream) {
        this.outputStream = outputStream;
    }

    public boolean isImmediateFlush() {
        return immediateFlush;
    }

    public void setImmediateFlush(boolean immediateFlush) {
        this.immediateFlush = immediateFlush;
    }

    @Override
    public void start() {
        if (!started) {
            started = true;
        }
    }

    @Override
    public void stop() {

    }

    @Override
    public boolean isStarted() {
        return started;
    }
}
