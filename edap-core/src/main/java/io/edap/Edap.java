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

import io.edap.util.CollectionUtils;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * Edap容器
 * @author: louis.lu
 * @date : 2019-07-08 11:49
 */
public class Edap {

    private Map<String, ServerGroup> serverGroups;

    public Edap() {
        serverGroups = new HashMap<>();
    }

    public Edap addServerGroup(ServerGroup serverGroup) {
        serverGroup.setEdap(this);
        serverGroups.put(serverGroup.getName(), serverGroup);
        return this;
    }

    public Edap addServerGroups(Map<String, ServerGroup> serverGroups) {
        for (ServerGroup sg : serverGroups.values()) {
            sg.setEdap(this);
        }
        this.serverGroups.putAll(serverGroups);
        return this;
    }

    public ServerGroup getServerGroup(String name) {
        return serverGroups.get(name);
    }

    public Map<String, ServerGroup> getServerGroups() {
        return this.serverGroups;
    }

    public void addServer(Server server) {
        ServerGroup sg = null;
        if (CollectionUtils.isEmpty(serverGroups)) {
            sg = new ServerGroup();
            sg.setEdap(this);
            serverGroups.put("default", sg);
        } else {
            for (Map.Entry<String, ServerGroup> entry : serverGroups.entrySet()) {
                if (sg == null) {
                    sg = entry.getValue();
                }
                if ("default".equals(entry.getKey())) {
                    sg = entry.getValue();
                }
            }
        }
        if (sg != null) {
            sg.addServer(server);
        }
    }

    public void run() throws IOException {
        if (CollectionUtils.isEmpty(serverGroups)) {
            return;
        }
        serverGroups.forEach((k, v) -> {
            System.out.println("ServerGroup [" + k + "] start");
            v.run();

        });

        int read = System.in.read();
    }
}
