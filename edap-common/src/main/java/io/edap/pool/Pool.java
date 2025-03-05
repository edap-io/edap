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

package io.edap.pool;

/**
 * 对象池接口
 * @param <E>
 */
public interface Pool<E> {
    /**
     * 从对象池获取一个池化对象
     * @return 池化对象，如果池中无对象则返回null
     */
    E borrow();

    /**
     * 向池中归还一个池化的对象
     * @param e 需要放入对象池的对象
     */
    void requite(E e);
}
