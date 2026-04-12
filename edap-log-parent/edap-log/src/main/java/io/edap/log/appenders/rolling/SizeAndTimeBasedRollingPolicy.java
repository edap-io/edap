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

public class SizeAndTimeBasedRollingPolicy extends TimeBasedRollingPolicy {
    private String maxFileSize;

    @Override
    public void start() {
        SizeAndTimeBasedFNATP satFNATP = new SizeAndTimeBasedFNATP();
        satFNATP.setMaxFileSize(maxFileSize);
        timeBasedFileNamingAndTriggeringPolicy = satFNATP;
        super.start();
    }

    /**
     * 设置最大文件大小的字符串值，可能会包含"KB，MB,GB"等字符
     */
    public String getMaxFileSize() {
        return maxFileSize;
    }

    public void setMaxFileSize(String maxFileSize) {
        this.maxFileSize = maxFileSize;
    }
}
