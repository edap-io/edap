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
 * 日志条目的类型。
 *
 * <p>与 etcd raft 的 {@code raftpb.EntryType} 一一对应。</p>
 */
public enum EntryType {

    /**
     * 普通命令，由客户端提出，最终应用到状态机。
     *
     * <p>leader 当选时追加的那条空命令（no-op）也是这个类型 ——
     * data 为空且长度为 0 即表示 no-op。</p>
     */
    NORMAL((byte) 0, "EntryNormal"),

    /**
     * 成员变更命令，data 中保存 {@link ConfChange} 的编码
     */
    CONFIG_CHANGE((byte) 1, "EntryConfChange"),

    /**
     * 成员变更命令（v2），data 中保存 {@link ConfChangeV2} 的编码
     */
    CONFIG_CHANGE_V2((byte) 2, "EntryConfChangeV2");

    private final byte   code;

    /** 与 etcd protobuf 枚举名一致的名字 */
    private final String label;

    EntryType(byte code, String label) {
        this.code = code;
        this.label = label;
    }

    public byte getCode() {
        return code;
    }

    /**
     * 与 etcd protobuf 枚举名一致的名字，例如 {@code EntryNormal}。
     *
     * <p>etcd 的 {@code DescribeEntry} 直接打印条目类型，得到的正是这个名字，
     * 这里保持一致，方便和上游输出逐字对照。</p>
     */
    @Override
    public String toString() {
        return label;
    }

    public static EntryType of(byte code) {
        for (EntryType type : values()) {
            if (type.code == code) {
                return type;
            }
        }
        throw new io.edap.raft.RaftException("Unknown log entry type: " + code);
    }
}
