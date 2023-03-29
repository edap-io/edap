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
 * 日志事件的编码器，将日志事件编码为字节数组，然后有Appender进行持久化
 */
public interface Encoder {
    /**
     * 编码日志事件为字节数组
     * @param logEvent 日志事件
     * @return 返回自己数组
     */
    void encode(LogOutputStream out, LogEvent logEvent);
}
