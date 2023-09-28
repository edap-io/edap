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

import io.edap.log.LifeCycle;
import io.edap.log.LogEvent;
import io.edap.log.appenders.FileAppender;
import io.edap.log.helps.ByteArrayBuilder;

import java.io.File;

/**
 * 触发日志滚动的触发器接口
 */
public interface TriggeringPolicy extends LifeCycle {

    /**
     * 判断当前日志事件是否达到滚动的条件
     * @param activeFile 当前使用的FileAppender实例
     * @param event 日志事件
     * @param builder 日志Encoder编码后的数据对象
     * @return 是否达到触发日志文件滚动的条件
     */
    boolean isTriggeringEvent(final File activeFile, LogEvent event, ByteArrayBuilder builder);

    void setFileAppender(FileAppender fileAppender);
}
