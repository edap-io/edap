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

package io.edap.raft;

/**
 * raft 的日志接口，等价于 etcd 的 {@code raft.Logger}。
 *
 * <p>etcd 的接口是 {@code Debugf/Infof/Warningf/Errorf}，格式串用 Go 的动词。
 * Java 侧保持同样的形状：方法名去掉 {@code f} 后缀，格式串的语义由
 * {@link GoFormat} 负责。</p>
 *
 * <p>raft 的日志是「给人看的」而不是「给机器解析的」，但 etcd 的交互测试
 * （datadriven）要逐字比对日志内容，所以格式必须与上游完全一致。</p>
 */
public interface RaftLogger {

    /**
     * 记录一条调试级别的日志
     *
     * @param format Go 风格的格式串
     * @param args   格式串里的参数
     */
    void debug(String format, Object... args);

    /**
     * 记录一条信息级别的日志
     *
     * @param format Go 风格的格式串
     * @param args   格式串里的参数
     */
    void info(String format, Object... args);

    /**
     * 记录一条警告级别的日志
     *
     * @param format Go 风格的格式串
     * @param args   格式串里的参数
     */
    void warn(String format, Object... args);

    /**
     * 记录一条错误级别的日志
     *
     * @param format Go 风格的格式串
     * @param args   格式串里的参数
     */
    void error(String format, Object... args);
}
