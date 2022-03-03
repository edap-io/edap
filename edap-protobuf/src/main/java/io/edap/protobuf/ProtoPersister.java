/*
 * Copyright 2020 The edap Project
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

package io.edap.protobuf;

import java.io.IOException;

/**
 * 根据javabean反射生成的proto文件的持久化器，用于在没有proto定义的javabean序列化反序列化版本不一致时，根据序列化的proto来保持兼容性的
 * 功能
 * @author : louis@easyea.com
 * @date : 2021/4/2
 */
public interface ProtoPersister {

    /**
     * 将制指定名称的proto的内容进行持久化，如果持久化失败则抛出IOException
     * @param beanName 需要持久化的bean的名称，使用类的全名
     * @param proto 生成的proto文件的字符串内容
     * @throws IOException 如果持久化失败则抛出异常
     */
    void persist(String beanName, String proto) throws IOException;

    /**
     * 根据类名获取该类原来进行编码的proto的内容，如果不存在则返回null或者空字符串，如果获取中有异常则抛出相应的异常
     * @param beanName 类名
     * @return 返回proto文件的内容
     * @throws IOException 如果持久化器获取时有异常则抛出异常
     */
    String getProto(String beanName) throws IOException;
}
