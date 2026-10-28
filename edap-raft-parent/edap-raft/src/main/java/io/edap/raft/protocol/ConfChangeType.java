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

/**
 * 成员变更的操作类型，等价于 etcd 的 {@code raftpb.ConfChangeType}。
 */
public enum ConfChangeType {

    /** 增加投票节点 */
    ADD_NODE((byte) 0, "ConfChangeAddNode"),
    /** 移除节点 */
    REMOVE_NODE((byte) 1, "ConfChangeRemoveNode"),
    /** 更新节点信息（地址等），不影响投票 */
    UPDATE_NODE((byte) 2, "ConfChangeUpdateNode"),
    /** 增加 learner（非投票成员） */
    ADD_LEARNER_NODE((byte) 3, "ConfChangeAddLearnerNode");

    private final byte   code;

    /** 与 etcd protobuf 枚举名一致的名字 */
    private final String label;

    ConfChangeType(byte code, String label) {
        this.code = code;
        this.label = label;
    }

    public byte getCode() {
        return code;
    }

    /**
     * 与 etcd protobuf 枚举名一致的名字，例如 {@code ConfChangeAddNode}。
     *
     * <p>etcd 用 {@code %v} 打印成员变更时得到的就是这个名字，
     * 这里保持一致，方便和上游日志逐字对照。</p>
     */
    @Override
    public String toString() {
        return label;
    }

    public static ConfChangeType of(byte code) {
        for (ConfChangeType type : values()) {
            if (type.code == code) {
                return type;
            }
        }
        throw new io.edap.raft.RaftException("Unknown conf change type: " + code);
    }
}
