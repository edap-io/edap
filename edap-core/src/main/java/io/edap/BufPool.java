/*
 * Copyright (c) 2019 louis.lu
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */

package io.edap;

import io.edap.buffer.FastBuf;

/**
 * 定义FastBuf的对象池接口
 * @author: louis.lu
 * @date : 2019/4/9 6:24 PM
 */
public interface BufPool {

    /**
     * 根据容量获取不小于该容量的FastBuf的对象，如果池中没有该大小的FastBuf则提供池中
     * 最大容量的FastBuf的对象
     * @param capacity 需要的容量
     * @return
     */
    FastBuf borrow(int capacity);

    /**
     * 将不再使用的FastBuf放入到池中
     * @param fastBuf 需要放入的FastBuf对象
     */
    void requite(FastBuf fastBuf);
}