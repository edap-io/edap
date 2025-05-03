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

public class IACCommand extends TelnetRequest {
    /**
     * IAC的命令
     */
    private IAC command;
    /**
     * IAC协商的内容
     */
    private IAC iac;
    /**
     * IAC协商的扩展选项
     */
    private int option;

    /**
     * IAC的命令
     */
    public IAC getCommand() {
        return command;
    }

    public void setCommand(IAC command) {
        this.command = command;
    }

    /**
     * IAC协商的内容
     */
    public IAC getIac() {
        return iac;
    }

    public void setIac(IAC iac) {
        this.iac = iac;
    }

    /**
     * IAC协商的扩展选项
     */
    public int getOption() {
        return option;
    }

    public void setOption(int option) {
        this.option = option;
    }
}
