/*
 * Copyright 2023 The edap Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package io.edap.erpc.server;

/**
 * 测试用的业务 DTO —— <b>不带任何协议注解</b>。
 *
 * <p>两种序列化器都要能编它：protobuf 走 getter/setter 推导，json 走 POJO 反射。
 * 字段刻意保持简单（String + int），避免两种编码器的行为差异干扰断言。</p>
 */
public class ErpcTestDto {

    private String name;
    private int    value;

    public ErpcTestDto() {
    }

    public ErpcTestDto(String name, int value) {
        this.name = name;
        this.value = value;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public int getValue() {
        return value;
    }

    public void setValue(int value) {
        this.value = value;
    }

    @Override
    public String toString() {
        return "ErpcTestDto{name=" + name + ", value=" + value + "}";
    }
}
