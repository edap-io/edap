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

package io.edap.raft.protocol;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Raft 节点之间的消息类型，与 etcd raft 的 {@code raftpb.MessageType} 一一对应。
 *
 * <p>序号与 etcd 保持一致，便于对照上游实现与测试用例。</p>
 */
public enum MessageType {

    /**
     * 本地消息：触发选举
     */
    MSG_HUP((byte) 0, "MsgHup"),

    /**
     * 本地消息：leader 广播心跳
     */
    MSG_BEAT((byte) 1, "MsgBeat"),

    /**
     * 客户端提案
     */
    MSG_PROP((byte) 2, "MsgProp"),

    /**
     * 日志复制
     */
    MSG_APP((byte) 3, "MsgApp"),

    /**
     * 日志复制响应
     */
    MSG_APP_RESP((byte) 4, "MsgAppResp"),

    /**
     * 拉票请求
     */
    MSG_VOTE((byte) 5, "MsgVote"),

    /**
     * 拉票响应
     */
    MSG_VOTE_RESP((byte) 6, "MsgVoteResp"),

    /**
     * 安装快照
     */
    MSG_SNAP((byte) 7, "MsgSnap"),

    /**
     * 心跳
     */
    MSG_HEARTBEAT((byte) 8, "MsgHeartbeat"),

    /**
     * 心跳响应
     */
    MSG_HEARTBEAT_RESP((byte) 9, "MsgHeartbeatResp"),

    /**
     * 目标节点不可达
     */
    MSG_UNREACHABLE((byte) 10, "MsgUnreachable"),

    /**
     * 快照状态报告
     */
    MSG_SNAP_STATUS((byte) 11, "MsgSnapStatus"),

    /**
     * 本地消息：leader 检查自己是否仍有多数派
     */
    MSG_CHECK_QUORUM((byte) 12, "MsgCheckQuorum"),

    /**
     * 转移领导权
     */
    MSG_TRANSFER_LEADER((byte) 13, "MsgTransferLeader"),

    /**
     * 接收方立即发起选举
     */
    MSG_TIMEOUT_NOW((byte) 14, "MsgTimeoutNow"),

    /**
     * 线性一致读请求
     */
    MSG_READ_INDEX((byte) 15, "MsgReadIndex"),

    /**
     * 线性一致读响应
     */
    MSG_READ_INDEX_RESP((byte) 16, "MsgReadIndexResp"),

    /**
     * 预投票请求
     */
    MSG_PRE_VOTE((byte) 17, "MsgPreVote"),

    /**
     * 预投票响应
     */
    MSG_PRE_VOTE_RESP((byte) 18, "MsgPreVoteResp"),

    /**
     * 异步存储：追加请求
     */
    MSG_STORAGE_APPEND((byte) 19, "MsgStorageAppend"),

    /**
     * 异步存储：追加响应
     */
    MSG_STORAGE_APPEND_RESP((byte) 20, "MsgStorageAppendResp"),

    /**
     * 异步存储：apply 请求
     */
    MSG_STORAGE_APPLY((byte) 21, "MsgStorageApply"),

    /**
     * 异步存储：apply 响应
     */
    MSG_STORAGE_APPLY_RESP((byte) 22, "MsgStorageApplyResp"),

    /**
     * 忘记 leader
     */
    MSG_FORGET_LEADER((byte) 23, "MsgForgetLeader");

    private static final Map<String, MessageType> BY_NAME;
    private static final Map<Byte, MessageType>    BY_CODE;
    private static final Map<String, MessageType> BY_GO_NAME;

    static {
        Map<String, MessageType> byName = new LinkedHashMap<>();
        Map<Byte, MessageType>    byCode = new LinkedHashMap<>();
        Map<String, MessageType> byGoName = new LinkedHashMap<>();
        for (MessageType type : values()) {
            byName.put(type.name(), type);
            byCode.put(type.code, type);
            byGoName.put(type.label, type);
        }
        BY_NAME = Collections.unmodifiableMap(byName);
        BY_CODE = Collections.unmodifiableMap(byCode);
        BY_GO_NAME = Collections.unmodifiableMap(byGoName);
    }

    private final byte    code;

    /**
     * 与 etcd protobuf 枚举名一致的标签，例如 {@code MSG_APP} 对应 {@code MsgApp}。
     */
    private final String label;

    MessageType(byte code, String label) {
        this.code = code;
        this.label = label;
    }

    /**
     * 与 etcd protobuf 枚举名一致的标签，例如 {@code MSG_APP} 对应 {@code MsgApp}。
     *
     * <p>调试输出、日志与测试期望值都用这个名字，保证与 etcd 完全一致。</p>
     */
    public String getLabel() {
        return label;
    }

    public byte getCode() {
        return code;
    }

    public static MessageType of(byte code) {
        MessageType type = BY_CODE.get(code);
        if (type == null) {
            throw new io.edap.raft.RaftException("Unknown raft message type: " + code);
        }
        return type;
    }

    /**
     * 按名字查找，与 etcd 的 {@code raftpb.MessageType_name} 对齐
     */
    public static MessageType ofName(String name) {
        MessageType type = BY_NAME.get(name);
        if (type == null) {
            throw new io.edap.raft.RaftException("Unknown raft message type name: " + name);
        }
        return type;
    }

    /**
     * 按 etcd protobuf 的枚举名查找，例如 {@code MsgApp}。
     */
    public static MessageType ofGoName(String name) {
        MessageType type = BY_GO_NAME.get(name);
        if (type == null) {
            throw new io.edap.raft.RaftException("Unknown raft message type name: " + name);
        }
        return type;
    }

    /**
     * 与 etcd protobuf 枚举名一致的名字，例如 {@code MSG_APP} 的名字是 {@code MsgApp}。
     *
     * <p>etcd 的 protobuf 会用这个名字做 {@code String()}，日志与调试输出都靠它，
     * 这里保持一致，方便和上游的输出逐字对照。</p>
     */
    @Override
    public String toString() {
        return label;
    }

    /**
     * 所有消息类型的「名字 → 类型」映射，顺序与 etcd 的 {@code MessageType_name} 一致
     */
    public static Map<String, MessageType> names() {
        return BY_NAME;
    }
}
