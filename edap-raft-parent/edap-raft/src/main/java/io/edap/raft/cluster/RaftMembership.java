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

import io.edap.raft.Peer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 成员表：nodeId → {@link RaftMember}，外加当前的 leader。
 *
 * <p><b>为什么需要自己维护</b>：raft 的 {@code ConfState} 只记 id，不记地址
 * （见 {@code ConfState} 的注释「节点地址由应用侧自己维护」）。
 * 编排层就是那个「应用侧」。</p>
 *
 * <p>地址有两个来源：</p>
 * <ol>
 *   <li>主在处理加入请求时直接把新成员的地址写进来，</li>
 *   <li>其它成员从 {@code ConfChange} 条目的 context 里解出来
 *       （见 {@link RaftPeerCodec}）。</li>
 * </ol>
 *
 * <p>本表是<b>进程内</b>的，跨重启靠 {@code dataDir/members.json} 恢复。</p>
 */
public class RaftMembership {

    private final Map<Long, RaftMember> members = new ConcurrentHashMap<>();

    private volatile long leaderId;

    /**
     * 加入/更新一个成员。
     */
    public void put(RaftMember member) {
        if (member == null) {
            return;
        }
        members.put(member.getId(), member);
    }

    /**
     * 按 id 取成员，没有时返回 null。
     */
    public RaftMember member(long id) {
        return members.get(id);
    }

    /**
     * 全部成员，按 id 升序。
     */
    public List<RaftMember> all() {
        List<RaftMember> out = new ArrayList<>(members.values());
        out.sort((a, b) -> Long.compare(a.getId(), b.getId()));
        return out;
    }

    /**
     * 成员数。
     */
    public int size() {
        return members.size();
    }

    public void remove(long id) {
        members.remove(id);
    }

    public void clear() {
        members.clear();
    }

    public long leaderId() {
        return leaderId;
    }

    public void setLeaderId(long leaderId) {
        this.leaderId = leaderId;
    }

    /**
     * 当前 leader 的成员视图；没有时返回 null。
     */
    public RaftMember leader() {
        return leaderId == 0 ? null : members.get(leaderId);
    }

    /**
     * 取某个成员的 raft 传输层 {@link Peer}（带 context）。
     * 没有时返回 null —— 调用方据此跳过发消息。
     */
    public Peer peerOf(long id) {
        RaftMember m = members.get(id);
        if (m == null) {
            return null;
        }
        byte[] context = RaftPeerCodec.encode(m.getHost(), m.getPort(), m.getManagerPort());
        return new Peer(id, m.getHost(), m.getPort(), context);
    }

    /**
     * 下一个可用的成员 id：当前最大 id + 1。
     *
     * <p>只由主在处理加入请求时调用，调用方负责串行化。</p>
     */
    public long nextMemberId() {
        long max = 0;
        for (Long id : members.keySet()) {
            if (id > max) {
                max = id;
            }
        }
        return max + 1;
    }

    @Override
    public String toString() {
        return "RaftMembership{leader=" + leaderId + ", members=" + members.size() + "}";
    }
}
