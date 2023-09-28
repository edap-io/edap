/*
 * Copyright 2022 The edap Project
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

package io.edap.log;

import java.io.IOException;

/**
 * 定义记录日志的Appender接口
 */
public interface Appender extends LifeCycle {

    /**
     * 拼写日志事件到存储中
     * @param logEvent 日志事件
     */
    void append(LogEvent logEvent) throws IOException;

    String getName();

    void setName(String name);

    LogWriter getLogoutStream();

}
