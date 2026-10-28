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

import io.edap.raft.log.RaftLog;
import io.edap.raft.protocol.Entry;

import java.util.List;

/**
 * etcd {@code diff_test.go} 里的 {@code diffu} / {@code ltoa} 辅助函数。
 *
 * <p>Go 版用外部 {@code diff -u} 命令生成差异；Java 侧改成纯文本的行级 diff，
 * 语义一致：两个字符串相同就返回空串，否则返回可读的差异。</p>
 */
public final class RaftDiffSupport {

    private RaftDiffSupport() {
    }

    /**
     * Go: diffu
     */
    public static String diffu(String a, String b) {
        if (a.equals(b)) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("--- base\n+++ other\n");
        String[] as = a.split("\n", -1);
        String[] bs = b.split("\n", -1);
        int max = Math.max(as.length, bs.length);
        for (int i = 0; i < max; i++) {
            String left = i < as.length ? as[i] : null;
            String right = i < bs.length ? bs[i] : null;
            if (left == null) {
                sb.append("+ ").append(right).append('\n');
            } else if (right == null) {
                sb.append("- ").append(left).append('\n');
            } else if (!left.equals(right)) {
                sb.append("- ").append(left).append('\n');
                sb.append("+ ").append(right).append('\n');
            }
        }
        return sb.toString();
    }

    /**
     * Go: ltoa
     *
     * <p>把日志状态渲染成可比较的文本。</p>
     */
    public static String ltoa(RaftLog l) {
        StringBuilder s = new StringBuilder();
        s.append("lastIndex: ").append(l.lastIndex()).append('\n');
        s.append("applied:  ").append(l.applied()).append('\n');
        s.append("applying:  ").append(l.applying()).append('\n');
        List<Entry> ents = l.allEntries();
        for (int i = 0; i < ents.size(); i++) {
            s.append('#').append(i).append(": ").append(describe(ents.get(i))).append('\n');
        }
        return s.toString();
    }

    /**
     * 等价于 protobuf 的 {@code pb.Entry.String()}，用于失败时对照。
     */
    private static String describe(Entry e) {
        StringBuilder s = new StringBuilder();
        s.append("term:").append(e.getTerm());
        s.append(" index:").append(e.getIndex());
        s.append(" type:").append(e.getType());
        byte[] data = e.getData();
        if (data != null && data.length > 0) {
            s.append(" data:\"").append(new String(data)).append('"');
        }
        return s.toString();
    }
}
