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

import io.edap.log.*;
import io.edap.log.helps.ByteArrayBuilder;
import io.edap.log.io.BaseLogOutputStream;
import io.edap.log.queue.LogDataQueue;
import io.edap.util.CollectionUtils;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import static io.edap.log.AbstractEncoder.LOCAL_BYTE_ARRAY_BUILDER;
import static io.edap.log.helpers.Util.printError;

public abstract class OutputStremAppender implements Appender {

    protected final ReentrantLock lock = new ReentrantLock(false);

    public final ReentrantLock compressLock = new ReentrantLock(false);

    protected Encoder encoder;

    private BaseLogOutputStream outputStream;

    private boolean immediateFlush = true;

    private String name;

    private boolean started;

    protected boolean async;
    protected LogDataQueue queue;

    protected static final AppenderManager APPENDER_MANAGER = AppenderManager.instance();


    public ReentrantLock getCompressLock() {
        return compressLock;
    }

    @Override
    public void setAsync(boolean async) {
        this.async = async;
    }

    public void setAsyncQueue(LogDataQueue queue) {
        this.queue = queue;
    }


    @Override
    public void append(LogEvent logEvent) throws IOException {
        if (!started || encoder == null) {
            if (!started) {
                printError("Apppender 还没有启动完成");
            }
            return;
        }
        if (async) {

        } else {
            syncAppend(logEvent);
        }
    }

    private void asyncAppend(LogEvent logEvent) {
        ByteArrayBuilder builder = LOCAL_BYTE_ARRAY_BUILDER.get();
        builder.reset();
        encoder.encode(logEvent, builder);
        builder.setOutputStream(getOutputStream());
        queue.publish(builder);
    }

    private void syncAppend(LogEvent logEvent) throws IOException {
        ByteArrayBuilder builder = encoder.encode(logEvent);
        writeData(builder);
    }

    @Override
    public void batchAppend(List<LogEvent> logEvents) throws IOException {
        if (!started || encoder == null) {
            if (!started) {
                printError("Apppender 还没有启动完成");
            }
            return;
        }
        if (CollectionUtils.isEmpty(logEvents)) {
            return;
        }
        int count = logEvents.size();
        ByteArrayBuilder builder = AbstractEncoder.LOCAL_BYTE_ARRAY_BUILDER.get();
        builder.reset();
        for (int i=0;i<count;i++) {
            encoder.encode(logEvents.get(i), builder);
        }
        writeData(builder);
    }

    protected void writeData(ByteArrayBuilder builder) throws IOException {
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

    protected void lockFreeWriteData(ByteArrayBuilder builder) throws IOException {
        lock.lock();
        builder.writeToLogOut(outputStream);
        if (isImmediateFlush()) {
            this.outputStream.flush();
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
    public LogWriter getLogoutStream() {
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
        try {
            this.outputStream.flush();
        } catch (IOException e) {
            printError("OutputStream.flush error", e);
        }
        try {
            this.outputStream.close();
        } catch (IOException e) {
            printError("OutputStream.flush error", e);
        }
        started = false;
    }

    public void closeOutputStream() {
        if (outputStream == null) {
            return;
        }
        try {
            this.outputStream.flush();
        } catch (IOException e) {
            printError("OutputStream.flush error", e);
        }
        try {
            this.outputStream.close();
        } catch (IOException e) {
            printError("OutputStream.flush error", e);
        }
    }

    @Override
    public boolean isStarted() {
        return started;
    }
}
