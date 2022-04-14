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

package io.edap.beanconvert;

/**
 * 定义Bean转换接口
 * @param <O> 原始Bean的class
 * @param <D> 目标Bean的Class
 */
@FunctionalInterface
public interface Convertor<O, D> {

    /**
     * 将一个javabean转换成另外一个javabean
     * @param orignal 需要被转换的javabean对象
     * @return 返回被转换的对象
     */
    D convert(O orignal);

}