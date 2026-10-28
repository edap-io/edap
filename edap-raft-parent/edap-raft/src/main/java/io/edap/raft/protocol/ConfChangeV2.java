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

import io.edap.raft.GoFormat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 成员变更（v2），等价于 etcd 的 {@code raftpb.ConfChangeV2}。
 *
 * <p>一次可以携带多条变更。多条变更必须走联合共识，
 * {@link #enterJoint()} 判断本次变更是否进入联合状态。</p>
 */
public class ConfChangeV2 implements ConfChangeI {

    private final ConfChangeTransition transition;
    private final List<ConfChangeSingle> changes;
    private final byte[]                context;

    public ConfChangeV2(ConfChangeTransition transition, List<ConfChangeSingle> changes, byte[] context) {
        this.transition = transition == null ? ConfChangeTransition.AUTO : transition;
        this.changes = changes == null ? Collections.emptyList() : Collections.unmodifiableList(new ArrayList<>(changes));
        this.context = context == null ? new byte[0] : context;
    }

    public ConfChangeTransition getTransition() {
        return transition;
    }

    public List<ConfChangeSingle> getChanges() {
        return changes;
    }

    public byte[] getContext() {
        return context;
    }

    /**
     * v2 自身
     */
    @Override
    public ConfChangeV2 asV2() {
        return this;
    }

    /**
     * v2 不能降级成 v1
     */
    @Override
    public ConfChange asV1() {
        return null;
    }

    /**
     * 判断本次变更是否进入联合状态。
     *
     * <p>返回值两个：</p>
     * <ul>
     *     <li>{@code autoLeave}：是否会自动退出联合状态</li>
     *     <li>{@code ok}：是否进入联合状态</li>
     * </ul>
     *
     * <p>与 etcd 一致：只要变更多于一条，或者显式要求联合共识，就走联合共识。</p>
     */
    public boolean[] enterJoint() {
        if (transition != ConfChangeTransition.AUTO || changes.size() > 1) {
            boolean autoLeave;
            switch (transition) {
                case AUTO:
                case JOINT_IMPLICIT:
                    autoLeave = true;
                    break;
                case JOINT_EXPLICIT:
                    autoLeave = false;
                    break;
                default:
                    throw new io.edap.raft.RaftException("unknown transition: " + transition);
            }
            return new boolean[]{autoLeave, true};
        }
        return new boolean[]{false, false};
    }

    /**
     * 本次变更是否退出联合状态。v2 里"零条变更"就表示退出。
     *
     * <p>与 etcd 一致：整个变更（不含 context）必须是零值才算退出联合状态。</p>
     */
    public boolean leaveJoint() {
        return transition == ConfChangeTransition.AUTO && changes.isEmpty()
                && (context == null || context.length == 0);
    }

    /**
     * 与 etcd 一致的可读描述。
     *
     * <p>etcd 的 {@code pb.ConfChangeV2} 的 {@code String()} 定义在指针接收者上，
     * 所以用 {@code %v} 打印值类型时走的是默认的结构体格式，
     * 得到 {@code {ConfChangeTransitionAuto [{ConfChangeAddNode 3}] []}}，这里保持一致。</p>
     */
    @Override
    public String toString() {
        StringBuilder buf = new StringBuilder();
        buf.append('{').append(transition).append(" [");
        for (int i = 0; i < changes.size(); i++) {
            if (i > 0) {
                buf.append(' ');
            }
            buf.append(changes.get(i));
        }
        buf.append("] ").append(GoFormat.goBytes(context)).append('}');
        return buf.toString();
    }
}
