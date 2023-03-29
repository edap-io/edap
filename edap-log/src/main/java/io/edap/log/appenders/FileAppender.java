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
import io.edap.log.io.BaseLogOutputStream;

import java.io.*;

public class FileAppender extends OutputStremAppender {

    private int bufferSize = 8192;

    protected String fileName = null;

    protected boolean append = true;

    private boolean prudent = false;

    public FileAppender() {

    }

    public void setFile(String file) {
        if (file == null) {
            this.fileName = file;
        } else {
            this.fileName = file.trim();
        }
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
            if (checkAndCreateFile()) {
                BufferedOutputStream bufOut;
                try {
                    bufOut = new BufferedOutputStream(new FileOutputStream(fileName, append), bufferSize);
                    super.setOutputStream(new BaseLogOutputStream(bufOut));
                    super.start();
                } catch (FileNotFoundException e) {
                    throw new RuntimeException(e);
                }
            }
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

    public void safeWrite(LogEvent logEvent) {

    }
}
