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

package io.edap.nio;

import java.io.IOException;
import java.nio.channels.Selector;

public interface SelectorProvider {

    default boolean enableFastDispatch() {
        return false;
    }

    /**
     * 打开一个Selector的实例，如果该实例能支持在select时使用自定义的调度器直接调度，则使用自定义的调度器调度替换
     * Selector中的selectedKeys等变量，省去select到数据结构后再由该线程进行调度的延迟。
     * @return
     * @throws IOException
     */
    default EdapSelectorInfo openSelector(NioEventDispatcher dispatcher) throws IOException {
        EdapSelectorInfo info = new EdapSelectorInfo();
        info.setSelector(Selector.open());
        info.setEventDispatcherSet(null);
        return info;
    }
}
