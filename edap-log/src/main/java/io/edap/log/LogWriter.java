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
 * 定义日志写入的接口方便与其他日志框架进行适配
 */
public interface LogWriter {

    /**
     * 日志数据持久化的接口，输出的数据为utf-8编码的字节数组，由于edap的日志对性能进行了优化所以讲直接格式化好的字节数组直接写入，
     * 不使用其他框架的日志格式化等api进行格式化
     * @param logData 日志数据
     */
    void write(byte[] logData);
}
