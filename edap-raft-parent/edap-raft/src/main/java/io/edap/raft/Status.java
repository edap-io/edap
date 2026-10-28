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

import io.edap.raft.tracker.Config;
import io.edap.raft.tracker.Progress;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;

/**
 * raft 节点当前的完整状态，等价于 etcd 的 {@code raft.Status}。
 *
 * <p>在 {@link BasicStatus} 的基础上补充了成员配置与各节点的复制进度。
 * 复制进度只有 leader 才有意义，因此非 leader 时 {@link #getProgress()} 是空表。</p>
 */
public class Status extends BasicStatus {

    /** 当前成员配置 */
    private final Config              config;

    /** 各节点的复制进度，只有 leader 才有 */
    private final Map<Long, Progress> progress;

    public Status(BasicStatus basicStatus, Config config, Map<Long, Progress> progress) {
        super(basicStatus.getId(), basicStatus.getHardState(), basicStatus.getSoftState(),
                basicStatus.getApplied(), basicStatus.getLeadTransferee());
        this.config = config;
        this.progress = progress == null ? Collections.emptyMap() : progress;
    }

    /**
     * 当前成员配置
     */
    public Config getConfig() {
        return config;
    }

    /**
     * 各节点的复制进度，只有 leader 才有
     */
    public Map<Long, Progress> getProgress() {
        return progress;
    }

    @Override
    public String toString() {
        return "Status{id=" + Long.toHexString(getId()) + ", hardState=" + getHardState()
                + ", softState=" + getSoftState() + ", applied=" + getApplied()
                + ", config=" + config + ", progress=" + progress
                + ", leadTransferee=" + Long.toHexString(getLeadTransferee()) + "}";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!super.equals(o)) {
            return false;
        }
        Status other = (Status) o;
        return Objects.equals(config, other.config)
                && Objects.equals(progress, other.progress);
    }

    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode(), config, progress);
    }
}
