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

package io.edap.raft;

import io.edap.raft.codec.ByteWriter;
import io.edap.raft.protocol.Message;
import io.edap.raft.quorum.AckedIndexer;
import io.edap.raft.quorum.Index;
import io.edap.raft.quorum.JointConfig;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 只读请求（ReadIndex）的跟踪器，等价于 etcd 的 {@code readOnly}。
 *
 * <p>leader 收到只读请求后先记下当时的提交位置，再广播一轮带上下文的心跳。
 * follower 原样回传上下文，leader 据此知道「谁已经确认过第几批请求」，
 * 收到多数派确认后就可以安全地回答读请求。</p>
 *
 * <p>这里的上下文编码与 etcd 不同：etcd 用小端 8 字节，edap-raft 全线使用
 * 大端（见 {@link io.edap.raft.codec.ByteWriter}）。上下文对两端是不透明字节，
 * 只要 leader 与 follower 用同一种编码即可。</p>
 */
public class ReadOnly implements AckedIndexer {

    /** 只读请求的处理方式 */
    private final ReadOnlyOption option;

    /** 各投票节点已确认到的请求位置 */
    private final Map<Long, Long> acks = new HashMap<>();

    /** 尚未被确认的读请求 */
    private final List<ReadIndexRequest> unconfirmedReads = new ArrayList<>();

    /**
     * 历史上被确认过的读请求总数，也就是从 {@link #unconfirmedReads} 头部
     * 已经移走多少个了。
     */
    private long confirmedReads;

    public ReadOnly(ReadOnlyOption option) {
        this.option = option == null ? ReadOnlyOption.READ_ONLY_SAFE : option;
    }

    /**
     * 只读请求的处理方式
     */
    public ReadOnlyOption getOption() {
        return option;
    }

    /**
     * 记下一个只读请求。
     *
     * @param commitIndex 收到请求时 raft 的提交位置
     * @param req         原始请求消息（可能是本地消息，也可能是别的节点转发过来的）
     */
    public void addRequest(long commitIndex, Message req) {
        unconfirmedReads.add(new ReadIndexRequest(req, commitIndex));
    }

    /**
     * 记录某个节点对心跳上下文的确认。
     *
     * @param from 确认方节点 id
     * @param ctx  心跳里带的上下文，为空表示这次心跳不携带确认信息
     */
    public void recvAck(long from, byte[] ctx) {
        if (ctx != null && ctx.length != 0) {
            long idx = readLong(ctx);
            Long old = acks.get(from);
            acks.put(from, old == null ? idx : Math.max(old, idx));
        }
    }

    /**
     * 查询某投票节点已确认到的位置，供 {@link JointConfig#committedIndex(AckedIndexer)} 使用。
     */
    @Override
    public Index ackedIndex(long voterId) {
        Long idx = acks.get(voterId);
        if (idx == null) {
            return null;
        }
        return new Index(idx);
    }

    /**
     * 用已有的确认信息和当前集群配置，尽可能多地确认未决的读请求。
     *
     * @return 本次被确认的读请求列表，没有则返回空列表
     */
    public List<ReadIndexRequest> maybeAdvance(JointConfig c) {
        // 用已提交位置算出现在能确认多少个读请求
        long newConfirmedReads = c.committedIndex(this).value();
        if (newConfirmedReads <= confirmedReads) {
            return new ArrayList<>();
        }
        int count = (int) (newConfirmedReads - confirmedReads);
        List<ReadIndexRequest> readStates = new ArrayList<>(
                unconfirmedReads.subList(0, Math.min(count, unconfirmedReads.size())));
        unconfirmedReads.subList(0, Math.min(count, unconfirmedReads.size())).clear();
        confirmedReads = newConfirmedReads;
        return readStates;
    }

    /**
     * 生成要随心跳发出的上下文，用来确认当前所有未决的读请求。
     *
     * <p>没有未决请求时返回 null（不携带上下文）。</p>
     */
    public byte[] heartbeatCtx() {
        if (unconfirmedReads.isEmpty()) {
            return null;
        }
        long unconfirmedReadPosition = confirmedReads + unconfirmedReads.size();
        ByteWriter writer = new ByteWriter(8);
        writer.writeLong(unconfirmedReadPosition);
        return writer.toByteArray();
    }

    /**
     * 未决读请求的数量
     */
    public int unconfirmedCount() {
        return unconfirmedReads.size();
    }

    /**
     * 解出上下文里的位置
     */
    private static long readLong(byte[] ctx) {
        long v = 0;
        for (int i = 0; i < 8; i++) {
            v = (v << 8) | (ctx[i] & 0xFFL);
        }
        return v;
    }

    /**
     * 一个未决的只读请求，等价于 etcd 的 {@code readIndexRequest}。
     */
    public static class ReadIndexRequest {

        /** 原始请求消息 */
        private final Message req;

        /** 收到请求时的提交位置 */
        private final long   index;

        public ReadIndexRequest(Message req, long index) {
            this.req = req;
            this.index = index;
        }

        /**
         * 原始请求消息
         */
        public Message getReq() {
            return req;
        }

        /**
         * 收到请求时的提交位置
         */
        public long getIndex() {
            return index;
        }
    }
}
