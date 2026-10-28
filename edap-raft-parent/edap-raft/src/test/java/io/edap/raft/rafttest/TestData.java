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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 一个数据驱动测试用例，等价于 cockroachdb/datadriven 的 {@code TestData}。
 */
public class TestData {

    private final String       cmd;
    private final List<CmdArg> cmdArgs;
    private final String       input;
    private final String       expected;
    private final String       path;
    private final int          line;

    public TestData(String cmd, List<CmdArg> cmdArgs, String input, String expected,
                    String path, int line) {
        this.cmd = cmd;
        this.cmdArgs = cmdArgs == null ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(cmdArgs));
        this.input = input == null ? "" : input;
        this.expected = expected == null ? "" : expected;
        this.path = path;
        this.line = line;
    }

    /**
     * 命令名，也就是命令行的第一个词
     */
    public String getCmd() {
        return cmd;
    }

    /**
     * 命令行参数
     */
    public List<CmdArg> getCmdArgs() {
        return cmdArgs;
    }

    /**
     * 命令的输入，命令块里第一行之后的内容
     */
    public String getInput() {
        return input;
    }

    /**
     * 期望输出，分隔线之后到空行之间的内容
     */
    public String getExpected() {
        return expected;
    }

    /**
     * 用例所在文件
     */
    public String getPath() {
        return path;
    }

    /**
     * 用例在文件里的行号，从 1 开始
     */
    public int getLine() {
        return line;
    }

    @Override
    public String toString() {
        return path + ":" + line + ": " + cmd;
    }
}
