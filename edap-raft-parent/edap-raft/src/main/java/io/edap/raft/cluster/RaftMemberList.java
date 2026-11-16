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

package io.edap.raft.cluster;

import java.util.ArrayList;
import java.util.List;

/**
 * 成员表的落盘包装：{@code dataDir/members.json}。
 *
 * <p>为什么要单独落盘：raft 的 {@code ConfState} 只记节点 id，不记地址。
 * 地址的唯一来源是 {@code ConfChange} 条目的 context ——
 * 日志被压缩之后那些条目就没了，地址会跟着丢。
 * 编排层自己留一份，重启时直接读回来。</p>
 */
public class RaftMemberList {

    private List<RaftMember> members = new ArrayList<>();

    public RaftMemberList() {
    }

    public RaftMemberList(List<RaftMember> members) {
        setMembers(members);
    }

    public List<RaftMember> getMembers() {
        return members;
    }

    public void setMembers(List<RaftMember> members) {
        this.members = members == null ? new ArrayList<>() : members;
    }
}
