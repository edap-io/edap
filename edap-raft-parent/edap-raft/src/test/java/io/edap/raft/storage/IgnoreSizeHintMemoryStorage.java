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

package io.edap.raft.storage;

import io.edap.raft.protocol.Entry;

import java.util.List;

/**
 * Go: {@code ignoreSizeHintMemStorage}。
 *
 * <p>读取日志时忽略调用方给的字节数上限，永远返回全部条目。
 * 用它复现「存储实现对大小的限制比 raft 内部宽松」的场景
 * （见 {@code TestNodeCommitPaginationAfterRestart}）。</p>
 */
public class IgnoreSizeHintMemoryStorage extends MemoryStorage {

    /**
     * 用给定的条目创建一个存储。
     *
     * <p>条目列表的第 0 个元素充当占位条目，它的 index 就是保留区间的起点。</p>
     */
    public IgnoreSizeHintMemoryStorage(List<Entry> ents) {
        super(ents);
    }

    @Override
    public synchronized List<Entry> entries(long lo, long hi, long maxSize) {
        return super.entries(lo, hi, Long.MAX_VALUE);
    }
}
