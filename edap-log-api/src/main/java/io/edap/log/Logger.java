/*
 * Copyright 2021 The edap Project
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

import java.util.function.Consumer;

/**
 * 定义日志的接口
 * @author louis
 */
public interface Logger {

    String ROOT_LOGGER_NAME = "ROOT";

    static int MAX_ARGS = 64;

    void trace(Object message);
    void trace(String msg, Throwable cause);
    void trace(String format, Consumer<LogArgs> logArgsConsumer);

    void debug(Object message);
    void debug(String msg, Throwable cause);
    void debug(String format, Consumer<LogArgs> logArgsConsumer);

    void conf(Object message);
    void conf(String msg, Throwable cause);
    void conf(String format, Consumer<LogArgs> logArgsConsumer);

    void info(Object message) ;
    void info(String msg, Throwable cause);
    void info(String format, Consumer<LogArgs> logArgsConsumer);

    void warn(Object message);
    void warn(String msg, Throwable cause);
    void warn(String format, Consumer<LogArgs> logArgsConsumer);

    void error(Object message);
    void error(String msg, Throwable t);
    void error(String format, Consumer<LogArgs> logArgsConsumer);

    int level();
    boolean isEnabled(int level);
    void level(int level);
}