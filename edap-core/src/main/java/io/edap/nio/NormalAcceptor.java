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

import io.edap.Acceptor;
import io.edap.Server;
import io.edap.util.CollectionUtils;

import java.util.ArrayList;
import java.util.List;

public class NormalAcceptor implements Acceptor {

    protected Server server;

    protected List<Server.Addr> addrs;

    @Override
    public void accept() {

    }

    @Override
    public void setServer(Server server) {
        this.server = server;
    }

    @Override
    public Server getServer() {
        return server;
    }

    @Override
    public void addAddrs(List<Server.Addr> addrs) {
        if (CollectionUtils.isEmpty(addrs)) {
            return;
        }
        if (this.addrs == null) {
            this.addrs = new ArrayList<>();
        }
        for (Server.Addr addr : addrs) {
            if (this.addrs.contains(addr)) {
                this.addrs.add(addr);
            }
        }
    }
}
