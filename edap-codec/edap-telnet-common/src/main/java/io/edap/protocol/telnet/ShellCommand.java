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

package io.edap.protocol.telnet;

/**
 * 普通的telnet命令
 */
public class ShellCommand extends TelnetRequest {
    /**
     * 当前所在目录
     */
    private String pwd;
    /**
     * 所执行的命令
     */
    private String command;
    /**
     * 执行命令的参数列表
     */
    private String[] args;

    /**
     * 当前所在目录
     */
    public String getPwd() {
        return pwd;
    }

    public void setPwd(String pwd) {
        this.pwd = pwd;
    }

    /**
     * 所执行的命令
     */
    public String getCommand() {
        return command;
    }

    public void setCommand(String command) {
        this.command = command;
    }

    /**
     * 执行命令的参数列表
     */
    public String[] getArgs() {
        return args;
    }

    public void setArgs(String[] args) {
        this.args = args;
    }
}
