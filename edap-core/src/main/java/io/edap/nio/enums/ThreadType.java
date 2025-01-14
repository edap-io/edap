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

package io.edap.nio.enums;

import java.util.Locale;

public enum ThreadType {
    /**
     * 标准的reaction的模式
     */
    REACTOR,
    /**
     * edap的线程模型，使用多个disruptor的队列来分配NIO的事件，该模式在使用Selector的select时使用反射将
     * selectedKeys的数据结构进行改造，在添加到该数据结构时直接将SelectionKey根据分配器直接分配到对应的disruptor队列
     * 进行消费，无需再遍历Set<SelectionKey>的数据结构进行分配事件。edap容器默认使用该模式，除非对应的JVM虚拟机不支持该
     * 模式或者使用者强制使用标准的reactor模式。
     */
    EDAP;
}