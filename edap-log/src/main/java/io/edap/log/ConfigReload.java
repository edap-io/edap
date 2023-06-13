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

/**
 * 配置文件重新加载的接口，继承该接口的类需要判断配置文件重新加载后是否需要进行变动需要变动的话，重新加载最新配置，并做相应的修改。
 */
public interface ConfigReload {

    /**
     * 重新加载新的配置文件
     * @param logConfig
     */
    void reload(LogConfig logConfig);
}
