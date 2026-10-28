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
 * 成员变更的过渡方式，等价于 etcd 的 {@code raftpb.ConfChangeTransition}。
 */
public enum ConfChangeTransition {

    /**
     * 自动：单条变更走简单协议，多条变更走联合共识。
     * 联合共识结束后自动退出联合状态。
     */
    AUTO((byte) 0, "ConfChangeTransitionAuto"),
    /**
     * 隐式联合共识：强制走联合共识，结束后自动退出。
     */
    JOINT_IMPLICIT((byte) 1, "ConfChangeTransitionJointImplicit"),
    /**
     * 显式联合共识：强制走联合共识，退出与否由后续的变更决定。
     */
    JOINT_EXPLICIT((byte) 2, "ConfChangeTransitionJointExplicit");

    private final byte   code;

    /** 与 etcd protobuf 枚举名一致的名字 */
    private final String label;

    ConfChangeTransition(byte code, String label) {
        this.code = code;
        this.label = label;
    }

    public byte getCode() {
        return code;
    }

    /**
     * 与 etcd protobuf 枚举名一致的名字，例如 {@code ConfChangeTransitionAuto}。
     *
     * <p>etcd 用 {@code %v} 打印成员变更时得到的就是这个名字，
     * 这里保持一致，方便和上游日志逐字对照。</p>
     */
    @Override
    public String toString() {
        return label;
    }

    public static ConfChangeTransition of(byte code) {
        for (ConfChangeTransition transition : values()) {
            if (transition.code == code) {
                return transition;
            }
        }
        throw new io.edap.raft.RaftException("Unknown conf change transition: " + code);
    }
}
