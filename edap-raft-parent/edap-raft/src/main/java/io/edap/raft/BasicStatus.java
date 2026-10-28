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

import io.edap.raft.protocol.HardState;

import java.util.Objects;

/**
 * raft 节点的基本状态，等价于 etcd 的 {@code raft.BasicStatus}。
 *
 * <p>只包含不需要额外分配的字段，{@link Status} 在它之上补充成员配置与复制进度。</p>
 */
public class BasicStatus {

    /** 本节点 id */
    private final long      id;

    /** 需要持久化的状态 */
    private final HardState hardState;

    /** 不需要持久化的状态 */
    private final SoftState softState;

    /** 已经应用到状态机的最大位置 */
    private final long      applied;

    /** 正在把领导权转移给谁，没有转移时是 0 */
    private final long      leadTransferee;

    public BasicStatus(long id, HardState hardState, SoftState softState, long applied, long leadTransferee) {
        this.id = id;
        this.hardState = hardState;
        this.softState = softState;
        this.applied = applied;
        this.leadTransferee = leadTransferee;
    }

    /**
     * 本节点 id
     */
    public long getId() {
        return id;
    }

    /**
     * 需要持久化的状态
     */
    public HardState getHardState() {
        return hardState;
    }

    /**
     * 不需要持久化的状态
     */
    public SoftState getSoftState() {
        return softState;
    }

    /**
     * 已经应用到状态机的最大位置
     */
    public long getApplied() {
        return applied;
    }

    /**
     * 正在把领导权转移给谁，没有转移时是 0
     */
    public long getLeadTransferee() {
        return leadTransferee;
    }

    @Override
    public String toString() {
        return "BasicStatus{id=" + Long.toHexString(id) + ", hardState=" + hardState
                + ", softState=" + softState + ", applied=" + applied
                + ", leadTransferee=" + Long.toHexString(leadTransferee) + "}";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        BasicStatus other = (BasicStatus) o;
        return id == other.id
                && applied == other.applied
                && leadTransferee == other.leadTransferee
                && Objects.equals(hardState, other.hardState)
                && Objects.equals(softState, other.softState);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, hardState, softState, applied, leadTransferee);
    }
}
