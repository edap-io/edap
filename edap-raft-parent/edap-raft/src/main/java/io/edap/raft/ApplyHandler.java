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

import io.edap.raft.protocol.Entry;

import java.util.List;

/**
 * 已提交条目的处理器。
 *
 * <p>设置 {@link RaftConfig#applyHandler} 之后，框架会起一条独立的 apply 线程，
 * 把已提交的条目交给这个处理器，而不是塞进 Ready 让应用层自己取。
 * 这样「推进 raft 状态机」「落盘」「应用」三件事各占一条线程，
 * Ready 周期里就只剩推进和落盘。</p>
 *
 * <p>调用约定：</p>
 * <ul>
 *     <li>同一个处理器可能被多次调用，每次调用是一批<b>连续且有序</b>的条目，
 *         index 从上次调用之后的下一个开始；</li>
 *     <li>回调在独立线程上发生，实现要自己保证线程安全；</li>
 *     <li>回调里不要做阻塞很久的事，否则 apply 会落后于提交；</li>
 *     <li>成员变更条目也在这个回调里出现，框架会负责把它们交给
 *         {@link Node#applyConfChange}。</li>
 * </ul>
 */
public interface ApplyHandler {

    /**
     * 应用一批已提交条目。
     *
     * @param entries 已提交的条目，按 index 递增
     */
    void apply(List<Entry> entries);
}
