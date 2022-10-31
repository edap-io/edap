/*
 * Copyright 2022 The edap Project
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

package io.edap.protobuf.model;

/**
 * 解析java代码后类型如果是特殊的Message比如java的void等，指定Message名称并包含需要导入那些proto文件
 */
public class MessageInfo {
    /**
     * Message的名称
     */
    private String messageName;
    /**
     * 需要导入的proto文件路径
     */
    private String impFile;

    /**
     * Message的名称
     */
    public String getMessageName() {
        return messageName;
    }

    public void setMessageName(String messageName) {
        this.messageName = messageName;
    }

    /**
     * 需要导入的proto文件路径
     */
    public String getImpFile() {
        return impFile;
    }

    public void setImpFile(String impFile) {
        this.impFile = impFile;
    }
}
