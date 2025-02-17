/*
 * Copyright 2023 The edap Project
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */

package io.edap.nio;

import com.lmax.disruptor.RingBuffer;
import io.edap.Server;
import io.edap.nio.enums.ThreadType;
import io.edap.nio.event.BizEvent;
import io.edap.nio.impl.DisruptorReadDispatcher;
import io.edap.nio.impl.ThreadPoolReadDispatcher;

public class ReadDispatcherFactory {

    private ThreadType threadType;

    public ReadDispatcherFactory(ThreadType threadType) {
        this.threadType = threadType;
    }

    public ReadDispatcher createReadDispatcher(Server server, RingBuffer<BizEvent>[] ringBuffers) {
        if (threadType == ThreadType.EDAP) {
            return new DisruptorReadDispatcher(server, ringBuffers);
        } else {
            return new ThreadPoolReadDispatcher();
        }
    }
}
