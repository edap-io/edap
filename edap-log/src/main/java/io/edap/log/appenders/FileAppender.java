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

import io.edap.log.LogEvent;
import io.edap.log.helps.ByteArrayBuilder;
import io.edap.log.io.BaseLogOutputStream;

import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import static io.edap.log.helpers.Util.printError;

public class FileAppender extends OutputStremAppender {

    private int bufferSize = 8192;

    protected String fileName = null;

    protected boolean append = true;

    private boolean prudent = false;

    private FileChannel fileChannel;

    public FileAppender() {

    }

    public ReentrantLock getLock() {
        return lock;
    }

    public void setFile(String file) {
        if (file == null) {
            this.fileName = file;
        } else {
            this.fileName = file.trim();
        }
    }

    final public String rawFileProperty() {
        return fileName;
    }

    public String getFile() {
        return fileName;
    }

    public void setPrudent(boolean prudent) {
        this.prudent = prudent;
    }

    public boolean isPrudent() {
        return prudent;
    }

    @Override
    public void start() {
        if (getFile() == null) {
            return;
        }
        try {
            lock.lock();
            if (!checkAndCreateFile()) {
                return;
            }
            BufferedOutputStream bufOut;
            try {
                FileOutputStream fos = new FileOutputStream(fileName, append);
                bufOut = new BufferedOutputStream(fos, bufferSize);
                fileChannel = fos.getChannel();
                super.setOutputStream(new BaseLogOutputStream(bufOut));
                super.start();
            } catch (FileNotFoundException e) {
                throw new RuntimeException(e);
            }
        } catch (Throwable t) {
            printError("FileAppender start error", t);
        } finally {
            lock.unlock();
        }
    }

    private boolean checkAndCreateFile() {
        File f = new File(fileName);
        if (f.exists()) {
            return true;
        }
        if (!f.getParentFile().exists()) {
            f.getParentFile().mkdirs();
        }
        try {
            return f.createNewFile();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void append(LogEvent logEvent) throws IOException {
        if (prudent) {
            safeWrite(logEvent);
        } else {
            super.append(logEvent);
        }
    }

    public void safeWrite(LogEvent logEvent) throws IOException {
        if (fileChannel == null || encoder == null) {
            return;
        }
        boolean interrupted = Thread.interrupted();
        ByteArrayBuilder builder = encoder.encode(logEvent);
        FileLock fileLock = null;
        try {
            fileLock = fileChannel.lock();
            long position = fileChannel.position();
            long size = fileChannel.size();
            if (size != position) {
                fileChannel.position(size);
            }
            super.writeData(builder);
        } catch (IOException e) {
            // Mainly to catch FileLockInterruptionExceptions (see LOGBACK-875)
            printError("", e);
        } finally {
            if (fileLock != null && fileLock.isValid()) {
                fileLock.release();
            }

            // Re-interrupt if we started in an interrupted state (see LOGBACK-875)
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
