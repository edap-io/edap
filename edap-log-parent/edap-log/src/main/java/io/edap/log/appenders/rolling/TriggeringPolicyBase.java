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

import io.edap.log.appenders.FileAppender;

public abstract class TriggeringPolicyBase implements TriggeringPolicy {

    protected FileAppender fileAppender;

    private boolean start;

    public void start() {
        start = true;
    }

    public void stop() {
        start = false;
    }

    public boolean isStarted() {
        return start;
    }

    @Override
    public void setFileAppender(FileAppender fileAppender) {
        this.fileAppender = fileAppender;
    }
}
