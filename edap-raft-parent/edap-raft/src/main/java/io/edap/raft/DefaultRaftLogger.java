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

import io.edap.log.Logger;
import io.edap.log.LoggerManager;

/**
 * 默认的 {@link RaftLogger}，把日志转交给 edap 的日志框架。
 *
 * <p>格式串先由 {@link GoFormat} 渲染成完整的一行，再交给底层日志器，
 * 这样底层日志器不需要理解 Go 的格式动词。</p>
 */
public class DefaultRaftLogger implements RaftLogger {

    /** 底层日志器 */
    private final Logger delegate;

    /**
     * 用 edap 的默认日志器创建一个 raft 日志器
     */
    public DefaultRaftLogger() {
        this(LoggerManager.getLogger(Raft.class));
    }

    /**
     * 用给定的日志器创建一个 raft 日志器
     *
     * @param delegate 底层日志器，null 表示不记录
     */
    public DefaultRaftLogger(Logger delegate) {
        this.delegate = delegate == null ? LoggerManager.NOP_LOGGER : delegate;
    }

    @Override
    public void debug(String format, Object... args) {
        delegate.debug(GoFormat.format(format, args));
    }

    @Override
    public void info(String format, Object... args) {
        delegate.info(GoFormat.format(format, args));
    }

    @Override
    public void warn(String format, Object... args) {
        delegate.warn(GoFormat.format(format, args));
    }

    @Override
    public void error(String format, Object... args) {
        delegate.error(GoFormat.format(format, args));
    }
}
