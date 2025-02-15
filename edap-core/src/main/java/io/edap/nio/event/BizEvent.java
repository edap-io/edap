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

package io.edap.nio.event;

import io.edap.NioSession;
import io.edap.ParseResult;
import io.edap.ServerChannelContext;

public class BizEvent {

    private ParseResult bizData;
    private NioSession nioSession;
    private ServerChannelContext serverChannelContext;

    public ParseResult getBizData() {
        return bizData;
    }

    public void setBizData(ParseResult bizData) {
        this.bizData = bizData;
    }

    public NioSession getNioSession() {
        return nioSession;
    }

    public void setNioSession(NioSession nioSession) {
        this.nioSession = nioSession;
    }

    public ServerChannelContext getServerChannelContext() {
        return serverChannelContext;
    }

    public void setServerChannelContext(ServerChannelContext serverChannelContext) {
        this.serverChannelContext = serverChannelContext;
    }
}
