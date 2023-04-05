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

package io.edap.log.appenders.rolling;

import io.edap.log.LogEvent;
import io.edap.log.helps.ByteArrayBuilder;
import io.edap.util.StringUtil;

import java.io.File;
import java.text.ParseException;
import java.util.Locale;
import java.util.concurrent.locks.ReentrantLock;

import static io.edap.log.helpers.Util.printError;

public class SizeBasedTriggeringPolicy extends TriggeringPolicyBase {

    /**
     * 默认最大文件大小为20MB
     */
    long maxLength = 20 * 1024 * 1024;

    private String maxFileSize;

    @Override
    public void start() {
        if (!StringUtil.isEmpty(maxFileSize)) {
            long size = parseFileSize(maxFileSize);
            if (size > 0) {
                maxLength = size;
            }
        }
        super.start();
    }

    public static long parseFileSize(String maxFileSize) {
        StringBuilder numStr = new StringBuilder();
        int index = 0;
        maxFileSize = maxFileSize.trim();
        char c1 = maxFileSize.charAt(index);
        if (c1 < '0' || c1 > '9') {
            printError("maxFileSize [" + maxFileSize + "] not number");
            return -1;
        }
        index++;
        numStr.append(c1);
        if (maxFileSize.length() > 1) {
            for (; index < maxFileSize.length(); index++) {
                char c = maxFileSize.charAt(index);
                if (c >= '0' && c <= '9') {
                    numStr.append(c);
                } else {
                    break;
                }
            }
        }
        try {
            long num = Long.parseLong(numStr.toString());
            if (index < maxFileSize.length()) {
                String unit = maxFileSize.substring(index).toUpperCase(Locale.ENGLISH);
                if ("KB".equals(unit) || "K".equals(unit)) {
                    num = num * 1024;
                } else if ("MB".equals(unit) || "M".equals(unit)) {
                    num = num * 1024 * 1024;
                } else if ("GB".equals(unit) || "G".equals(unit)) {
                    num = num * 1024 * 1024 * 1024;
                } else {
                    throw new ParseException("not support Unit [" + unit + "]", 0);
                }
            }
            return num;
        } catch (Throwable t) {
            printError("parse maxFileSize error", t);
        }
        return -1;
    }

    @Override
    public boolean isTriggeringEvent(final File activeFile, LogEvent event, ByteArrayBuilder builder) {
        if (activeFile.length() + builder.length() > maxLength) {
            ReentrantLock lock = fileAppender.getCompressLock();
            if (lock.isLocked()) {
                printError("Compress havn't over can't rollover");
                return false;
            } else {
                return true;
            }
        }
        return false;
    }

    public String getMaxFileSize() {
        return maxFileSize;
    }

    public void setMaxFileSize(String maxFileSize) {
        this.maxFileSize = maxFileSize;
    }
}
