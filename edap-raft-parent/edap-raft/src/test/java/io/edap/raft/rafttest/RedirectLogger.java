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

package io.edap.raft.rafttest;

import io.edap.raft.GoFormat;
import io.edap.raft.RaftLogger;

/**
 * 把日志重定向到内存里的输出器，等价于 etcd rafttest 的 {@code RedirectLogger}。
 *
 * <p>每一行日志前面会加上级别名（DEBUG / INFO / WARN / ERROR），
 * 与 etcd 的输出格式一致，方便和数据驱动测试文件里的期望输出逐字比对。</p>
 */
public class RedirectLogger implements RaftLogger {

    /** 级别名，顺序与 etcd rafttest 一致 */
    public static final String[] LEVEL_NAMES = {"DEBUG", "INFO", "WARN", "ERROR", "FATAL", "NONE"};

    /** DEBUG 的级别值 */
    public static final int LEVEL_DEBUG = 0;
    /** INFO 的级别值 */
    public static final int LEVEL_INFO = 1;
    /** WARN 的级别值 */
    public static final int LEVEL_WARN = 2;
    /** ERROR 的级别值 */
    public static final int LEVEL_ERROR = 3;
    /** FATAL 的级别值 */
    public static final int LEVEL_FATAL = 4;
    /** NONE 的级别值，什么都不输出 */
    public static final int LEVEL_NONE = 5;

    /** 输出缓冲区 */
    private StringBuilder builder = new StringBuilder();

    /** 当前级别，值越小输出的内容越多 */
    private int lvl = LEVEL_DEBUG;

    /**
     * 清空输出缓冲区
     */
    public void reset() {
        builder.setLength(0);
    }

    /**
     * 当前级别
     */
    public int getLevel() {
        return lvl;
    }

    /**
     * 设置当前级别
     */
    public void setLevel(int lvl) {
        this.lvl = lvl;
    }

    /**
     * 是否已经静音（级别为 NONE）
     */
    public boolean isQuiet() {
        return lvl == LEVEL_NAMES.length - 1;
    }

    /**
     * 输出缓冲区里的内容长度
     */
    public int length() {
        return builder.length();
    }

    /**
     * 输出缓冲区里的内容
     */
    public String string() {
        return builder.toString();
    }

    /**
     * 直接往输出缓冲区里写内容，级别为 NONE 时也会写。
     *
     * <p>etcd 里缩进输出走的是 {@code strings.Builder} 而不是 {@code RedirectLogger}，
     * 所以不受静音影响，这里保持一致。</p>
     */
    public void writeRaw(String s) {
        builder.append(s);
    }

    /**
     * 取走当前的输出缓冲区，同时换上一个新的空缓冲区。
     */
    public StringBuilder takeBuilder() {
        StringBuilder b = builder;
        builder = new StringBuilder();
        return b;
    }

    /**
     * 换回之前取走的输出缓冲区
     */
    public void restoreBuilder(StringBuilder b) {
        builder = b;
    }

    /**
     * 往输出里写一段内容，级别为 NONE 时不写
     */
    public void write(String s) {
        if (isQuiet()) {
            return;
        }
        builder.append(s);
    }

    @Override
    public void debug(String format, Object... args) {
        printf(LEVEL_DEBUG, format, args);
    }

    @Override
    public void info(String format, Object... args) {
        printf(LEVEL_INFO, format, args);
    }

    @Override
    public void warn(String format, Object... args) {
        printf(LEVEL_WARN, format, args);
    }

    @Override
    public void error(String format, Object... args) {
        printf(LEVEL_ERROR, format, args);
    }

    /**
     * 按级别输出一行日志。
     *
     * <p>与 etcd rafttest 一致：级别不够高时什么都不输出；
     * 格式串本身不带换行时才补一个换行。</p>
     */
    private void printf(int level, String format, Object... args) {
        if (lvl > level) {
            return;
        }
        builder.append(LEVEL_NAMES[level]).append(' ');
        builder.append(GoFormat.format(format, args));
        if (format == null || format.isEmpty() || format.charAt(format.length() - 1) != '\n') {
            builder.append('\n');
        }
    }
}
