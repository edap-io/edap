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
package io.edap.log;

import java.io.OutputStream;

/**
 * 日志适配器，当edap-log和logback等日志框架并存时可以通过适配logback等日志框框架的配置以及相应的日志文件，将edap的日志打印到
 * logback的日志文件中
 */
public interface LogAdapter {

    /**
     * 启动时从配置文件解析为edap-log的配置文件
     * @return 返回edap-log的配置文件
     */
    LogConfig loadConfig();

    /**
     * 为配置文件的更新增加监听器，当配置文件有变更时通知监听器，来重新加载配置文件
     * @return 最新的edap-log的配置文件
     */
    void registerListener(ConfigAlterationListener listener);

    OutputStream getOutputStream(String appenderName);
}
