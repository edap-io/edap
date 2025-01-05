/*
 * Copyright 2023 The edap Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package io.edap.log;

import io.edap.util.CollectionUtils;

import java.lang.reflect.Constructor;
import java.util.List;

import static io.edap.log.helpers.Util.printError;

/**
 *
 * @param <E> 发布的数据类型
 * @param <D> 队列中存储的数据类型
 */
public interface LogQueue<E, D> {

    void publish(E e);

    void start();

    void stop();

    /**
     * 设置参数，如果该参数不符合规范则抛出异常，使用默认参数
     * @param arg 配置的参数
     * @throws Throwable 如果参数不符合规范抛出的异常
     */
    void setArg(LogConfig.ArgNode arg) throws Throwable;

    static Object instance(Class queueClass, List<LogConfig.ArgNode> args)
            throws RuntimeException {
        Constructor<?>[] consts = queueClass.getDeclaredConstructors();
        LogQueue queue = null;
        for (Constructor c : consts) {
            if (c.getTypeParameters().length == 0) {
                try {
                    queue = (LogQueue)c.newInstance();
                } catch (Throwable t) {
                    throw new RuntimeException(queueClass.getName() + " newInstance error ", t);
                }
            }
        }
        if (queue == null) {
            throw new RuntimeException(queueClass.getName() + " have't default Constructor");
        }
        if (!CollectionUtils.isEmpty(args)) {
            for (LogConfig.ArgNode arg : args) {
                try {
                    queue.setArg(arg);
                } catch (Throwable t) {
                    printError(queueClass.getName() + " setArg " + arg.getName() + " error", t);
                }
            }
        }
        return queue;
    }
}
