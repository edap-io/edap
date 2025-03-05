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

import io.edap.Server;
import io.edap.nio.enums.ThreadType;
import io.edap.nio.impl.DisruptorAcceptDispatcher;
import io.edap.nio.impl.ThreadPoolAcceptDispatcher;

public class AcceptDispatcherFactory {

    private ThreadType threadType;

    public AcceptDispatcherFactory(ThreadType threadType) {
        this.threadType = threadType;
    }

    public AcceptDispatcher createAcceptDispatcher(Server server) {
        if (threadType == ThreadType.EDAP) {
            return new DisruptorAcceptDispatcher(server);
        } else {
            return new ThreadPoolAcceptDispatcher();
        }
    }
}
