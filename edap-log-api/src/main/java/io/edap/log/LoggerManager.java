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

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.function.Consumer;

/**
 * 日志管理器
 * @author louis
 */
public class LoggerManager {

    private LoggerManager() {}

    public static Logger getLogger(Class clazz) {
        return getLogger(clazz.getName());
    }

    public static Logger getLogger(String name) {
        Logger log = null;
        try {
            Class logClazz = Class.forName("io.edap.x.log.LoggerImpl");

            Constructor ctc = logClazz.getConstructor(String.class);

            log = (Logger)ctc.newInstance(name);
            log.level(LogLevel.CONF);
        } catch (ClassNotFoundException e) {

        } catch (IllegalAccessException e) {

        } catch (InstantiationException e) {

        } catch (NoSuchMethodException e) {

        } catch (InvocationTargetException e) {

        }
        if (log == null) {
            log = new Logger() {
                @Override
                public void trace(Object message) {

                }

                @Override
                public void trace(String msg, Throwable cause) {

                }

                @Override
                public void trace(String format, Consumer<LogArgs> logArgsConsumer) {

                }

                @Override
                public void debug(Object message) {

                }

                @Override
                public void debug(String msg, Throwable cause) {

                }

                @Override
                public void debug(String format, Consumer<LogArgs> logArgsConsumer) {

                }

                @Override
                public void conf(Object message) {

                }

                @Override
                public void conf(String msg, Throwable cause) {

                }

                @Override
                public void conf(String format, Consumer<LogArgs> logArgsConsumer) {

                }

                @Override
                public void info(Object message) {

                }

                @Override
                public void info(String msg, Throwable cause) {

                }

                @Override
                public void info(String format, Consumer<LogArgs> logArgsConsumer) {

                }

                @Override
                public void warn(Object message) {

                }

                @Override
                public void warn(String msg, Throwable cause) {

                }

                @Override
                public void warn(String format, Consumer<LogArgs> logArgsConsumer) {

                }

                @Override
                public void error(Object message) {

                }

                @Override
                public void error(String msg, Throwable t) {

                }

                @Override
                public void error(String format, Consumer<LogArgs> logArgsConsumer) {

                }

                @Override
                public int level() {
                    return 0;
                }

                @Override
                public boolean isEnabled(int level) {
                    return false;
                }

                @Override
                public void level(int level) {

                }
            };
        }
        return log;
    }
}
