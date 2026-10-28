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

import io.edap.raft.protocol.ConfChangeCodec;
import io.edap.raft.protocol.ConfState;
import io.edap.raft.protocol.Entry;
import io.edap.raft.protocol.EntryType;
import io.edap.raft.protocol.HardState;
import io.edap.raft.protocol.Message;
import io.edap.raft.protocol.MessageType;
import io.edap.raft.protocol.RaftCodec;
import io.edap.raft.protocol.Snapshot;
import io.edap.raft.protocol.SnapshotMetadata;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;

/**
 * raft 的一些零散工具函数，等价于 etcd 的 {@code util.go}。
 */
public final class RaftUtil {

    private RaftUtil() {
    }

    /**
     * 本地追加线程的 id。
     *
     * <p>etcd 用 {@code math.MaxUint64} 表示「本地线程」，这里用 -1，
     * 语义一样：正常节点的 id 不会取到这两个值。</p>
     */
    public static final long LOCAL_APPEND_THREAD = -1L;

    /**
     * 本地应用线程的 id。
     */
    public static final long LOCAL_APPLY_THREAD = -2L;

    /** 本地消息类型：不走网络，只在本节点内部流转 */
    private static final EnumSet<MessageType> LOCAL_MSGS = EnumSet.of(
            MessageType.MSG_HUP,
            MessageType.MSG_BEAT,
            MessageType.MSG_UNREACHABLE,
            MessageType.MSG_SNAP_STATUS,
            MessageType.MSG_CHECK_QUORUM,
            MessageType.MSG_STORAGE_APPEND,
            MessageType.MSG_STORAGE_APPEND_RESP,
            MessageType.MSG_STORAGE_APPLY,
            MessageType.MSG_STORAGE_APPLY_RESP);

    /** 响应类的消息类型 */
    private static final EnumSet<MessageType> RESPONSE_MSGS = EnumSet.of(
            MessageType.MSG_APP_RESP,
            MessageType.MSG_VOTE_RESP,
            MessageType.MSG_HEARTBEAT_RESP,
            MessageType.MSG_UNREACHABLE,
            MessageType.MSG_READ_INDEX_RESP,
            MessageType.MSG_PRE_VOTE_RESP,
            MessageType.MSG_STORAGE_APPEND_RESP,
            MessageType.MSG_STORAGE_APPLY_RESP);

    /**
     * 该消息类型是不是只在本节点内部流转
     */
    public static boolean isLocalMsg(MessageType type) {
        return type != null && LOCAL_MSGS.contains(type);
    }

    /**
     * 该消息类型是不是响应类的消息
     */
    public static boolean isResponseMsg(MessageType type) {
        return type != null && RESPONSE_MSGS.contains(type);
    }

    /**
     * 该 id 是不是本地线程
     */
    public static boolean isLocalMsgTarget(long id) {
        return id == LOCAL_APPEND_THREAD || id == LOCAL_APPLY_THREAD;
    }

    /**
     * 两个硬状态是否相等
     */
    public static boolean isHardStateEqual(HardState a, HardState b) {
        if (a == null || b == null) {
            return a == b;
        }
        return a.getTerm() == b.getTerm()
                && a.getVote() == b.getVote()
                && a.getCommit() == b.getCommit();
    }

    /**
     * 硬状态是否为空（term、vote、commit 都是 0，或者干脆是 null）
     */
    public static boolean isEmptyHardState(HardState st) {
        if (st == null) {
            return true;
        }
        return st.getTerm() == 0 && st.getVote() == 0 && st.getCommit() == 0;
    }

    /**
     * 快照是否为空
     */
    public static boolean isEmptySnap(Snapshot snap) {
        return snap == null || snap.getMetadata() == null || snap.getMetadata().getIndex() == 0;
    }

    /**
     * 条目数据的格式化器，由使用方提供，等价于 etcd 的 {@code EntryFormatter}。
     *
     * <p>用于把条目的二进制负载渲染成可读文本。</p>
     */
    public interface EntryFormatter {

        /**
         * 格式化条目数据
         */
        String format(byte[] data);
    }

    /**
     * 条目类型的可读名字，等价于 etcd protobuf 里 {@code EntryType} 的枚举名。
     *
     * <p>etcd 用 protobuf 生成 {@code String()}，得到的是 {@code EntryNormal} 这样的名字；
     * Java 侧的枚举常量叫 {@code NORMAL}，这里做一次映射，
     * 保证调试输出的文本与 etcd 完全一致。</p>
     */
    private static String entryTypeName(EntryType type) {
        return type == null ? "null" : type.toString();
    }

    /**
     * 条目的可读描述，等价于 etcd 的 {@code DescribeEntry}。
     *
     * <p>格式是 {@code term/index EntryType payload}，
     * payload 为空时整段省略。</p>
     *
     * @param e 条目
     * @param f 负载格式化器，null 表示用默认的「带引号的原始字节」
     */
    public static String describeEntry(Entry e, EntryFormatter f) {
        EntryFormatter formatter = f == null ? RaftUtil::quote : f;
        String formatted;
        switch (e.getType()) {
            case NORMAL:
                formatted = formatter.format(e.getData());
                break;
            case CONFIG_CHANGE:
            case CONFIG_CHANGE_V2:
                try {
                    formatted = ConfChangeCodec.changesToString(
                            ConfChangeCodec.unmarshal(e.getType(), e.getData()).asV2().getChanges());
                } catch (RuntimeException ex) {
                    formatted = ex.getMessage();
                }
                break;
            default:
                formatted = "";
                break;
        }
        if (formatted != null && !formatted.isEmpty()) {
            formatted = " " + formatted;
        } else {
            formatted = "";
        }
        return e.getTerm() + "/" + e.getIndex() + " " + entryTypeName(e.getType()) + formatted;
    }

    /**
     * 把字节数组渲染成 Go 的 {@code %q} 形式，即带双引号的字符串字面量。
     *
     * <p>可打印的 ASCII 原样输出，控制字符用 {@code \n} / {@code \t} / {@code \xNN} 表示，
     * 其余不可打印字符用 {@code \\uXXXX} 表示。</p>
     */
    public static String quote(byte[] data) {
        if (data == null) {
            return "\"\"";
        }
        StringBuilder sb = new StringBuilder(data.length + 2);
        sb.append('"');
        for (byte b : data) {
            int c = b & 0xFF;
            switch (c) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                case 0x07:            // bell，Java 没有 \a 转义
                    sb.append("\\a");
                    break;
                case '\b':
                    sb.append("\\b");
                    break;
                case '\f':
                    sb.append("\\f");
                    break;
                case 0x0B:            // vertical tab，Java 没有 \v 转义
                    sb.append("\\v");
                    break;
                default:
                    if (c >= 0x20 && c < 0x7F) {
                        sb.append((char) c);
                    } else if (c < 0x20 || c == 0x7F) {
                        sb.append(String.format("\\x%02x", c));
                    } else {
                        sb.append(String.format("\\u%04x", c));
                    }
                    break;
            }
        }
        sb.append('"');
        return sb.toString();
    }

    /**
     * 截取最长的前缀，使总字节数不超过 maxSize。
     *
     * <p>只要入参非空，就至少返回一条（etcd 的 limitSize 语义）：
     * 即使第一条就超了额度，也照样返回它。</p>
     */
    public static List<Entry> limitSize(List<Entry> ents, long maxSize) {
        if (ents.isEmpty()) {
            return Collections.emptyList();
        }
        long size = RaftCodec.entrySize(ents.get(0));
        for (int limit = 1; limit < ents.size(); limit++) {
            size += RaftCodec.entrySize(ents.get(limit));
            if (size > maxSize) {
                return new ArrayList<>(ents.subList(0, limit));
            }
        }
        return ents;
    }

    /**
     * 软状态的可读描述，等价于 etcd 的 {@code DescribeSoftState}。
     *
     * <p>格式是 {@code Lead:0 State:StateFollower}。</p>
     */
    public static String describeSoftState(SoftState ss) {
        return "Lead:" + ss.getLead() + " State:" + ss.getRaftState();
    }

    /**
     * 硬状态的可读描述，等价于 etcd 的 {@code DescribeHardState}。
     *
     * <p>格式是 {@code Term:1 Vote:1 Commit:2}，vote 为 0 时省略。</p>
     */
    public static String describeHardState(HardState hs) {
        StringBuilder buf = new StringBuilder();
        buf.append("Term:").append(hs.getTerm());
        if (hs.getVote() != 0) {
            buf.append(" Vote:").append(hs.getVote());
        }
        buf.append(" Commit:").append(hs.getCommit());
        return buf.toString();
    }

    /**
     * 成员配置的可读描述，等价于 etcd 的 {@code DescribeConfState}。
     *
     * <p>etcd 用 {@code %v} 打印 {@code []uint64}，得到的是 {@code [1 2]} 这种形式，
     * 这里保持一致。</p>
     */
    public static String describeConfState(ConfState state) {
        return "Voters:" + GoFormat.goSlice(state.getVoters())
                + " VotersOutgoing:" + GoFormat.goSlice(state.getVotersOutgoing())
                + " Learners:" + GoFormat.goSlice(state.getLearners())
                + " LearnersNext:" + GoFormat.goSlice(state.getLearnersNext())
                + " AutoLeave:" + state.isAutoLeave();
    }

    /**
     * 快照的可读描述，等价于 etcd 的 {@code DescribeSnapshot}。
     */
    public static String describeSnapshot(Snapshot snap) {
        SnapshotMetadata m = snap.getMetadata();
        return "Index:" + m.getIndex() + " Term:" + m.getTerm()
                + " ConfState:" + describeConfState(m.getConfState());
    }

    /**
     * 一批只读请求状态的可读描述。
     *
     * <p>etcd 用 {@code %v} 打印 {@code []pb.ReadState}，得到的是 {@code [{1 [1 2]}]} 这种形式，
     * 元素之间用空格分隔，这里保持一致。</p>
     */
    public static String describeReadStates(List<ReadState> states) {
        if (states == null || states.isEmpty()) {
            return "[]";
        }
        StringBuilder buf = new StringBuilder();
        buf.append('[');
        for (int i = 0; i < states.size(); i++) {
            if (i > 0) {
                buf.append(' ');
            }
            buf.append(states.get(i));
        }
        return buf.append(']').toString();
    }

    /**
     * 一批条目的可读描述，等价于 etcd 的 {@code DescribeEntries}。
     *
     * <p>每条一行。</p>
     */    public static String describeEntries(List<Entry> ents, EntryFormatter f) {
        StringBuilder buf = new StringBuilder();
        for (Entry e : ents) {
            buf.append(describeEntry(e, f)).append('\n');
        }
        return buf.toString();
    }

    /**
     * 消息的可读描述，等价于 etcd 的 {@code DescribeMessage}。
     */
    public static String describeMessage(Message m, EntryFormatter f) {
        return describeMessageWithIndent("", m, f);
    }

    /**
     * 带缩进的消息描述，等价于 etcd 的 {@code describeMessageWithIndent}。
     */
    private static String describeMessageWithIndent(String indent, Message m, EntryFormatter f) {
        StringBuilder buf = new StringBuilder();
        buf.append(indent)
                .append(describeTarget(m.getFrom()))
                .append("->")
                .append(describeTarget(m.getTo()))
                .append(' ')
                .append(m.getType())
                .append(" Term:").append(m.getTerm())
                .append(" Log:").append(m.getLogTerm()).append('/').append(m.getIndex());
        if (m.getReject()) {
            buf.append(" Rejected (Hint: ").append(m.getRejectHint()).append(')');
        }
        if (m.getCommit() != 0) {
            buf.append(" Commit:").append(m.getCommit());
        }
        if (m.getVote() != 0) {
            buf.append(" Vote:").append(m.getVote());
        }
        List<Entry> ents = m.getEntries();
        int ln = ents == null ? 0 : ents.size();
        if (ln == 1) {
            buf.append(" Entries:[").append(describeEntry(ents.get(0), f)).append(']');
        } else if (ln > 1) {
            buf.append(" Entries:[");
            for (Entry e : ents) {
                buf.append('\n').append(indent).append("  ");
                buf.append(describeEntry(e, f));
            }
            buf.append('\n').append(indent).append(']');
        }
        Snapshot snap = m.getSnapshot();
        if (snap != null && !isEmptySnap(snap)) {
            buf.append('\n').append(indent).append("  Snapshot: ").append(describeSnapshot(snap));
        }
        List<Message> responses = m.getResponses();
        if (responses != null && !responses.isEmpty()) {
            buf.append(" Responses:[");
            for (Message resp : responses) {
                buf.append('\n');
                buf.append(describeMessageWithIndent(indent + "  ", resp, f));
            }
            buf.append('\n').append(indent).append(']');
        }
        return buf.toString();
    }

    /**
     * 消息收发方的可读名字，等价于 etcd 的 {@code describeTarget}。
     *
     * <p>本地线程有专门的名字，其余用十六进制。</p>
     */
    private static String describeTarget(long id) {
        if (id == Raft.NONE) {
            return "None";
        }
        if (id == LOCAL_APPEND_THREAD) {
            return "AppendThread";
        }
        if (id == LOCAL_APPLY_THREAD) {
            return "ApplyThread";
        }
        return Long.toHexString(id);
    }

    /**
     * 一批工作的可读描述，等价于 etcd 的 {@code DescribeReady}。
     */
    public static String describeReady(Ready rd, EntryFormatter f) {
        StringBuilder buf = new StringBuilder();
        if (rd.getSoftState() != null) {
            buf.append(describeSoftState(rd.getSoftState())).append('\n');
        }
        if (!isEmptyHardState(rd.getHardState())) {
            buf.append("HardState ").append(describeHardState(rd.getHardState())).append('\n');
        }
        if (!rd.getReadStates().isEmpty()) {
            buf.append("ReadStates ").append(describeReadStates(rd.getReadStates())).append('\n');
        }
        if (!rd.getEntries().isEmpty()) {
            buf.append("Entries:\n");
            buf.append(describeEntries(rd.getEntries(), f));
        }
        if (!isEmptySnap(rd.getSnapshot())) {
            buf.append("Snapshot ").append(describeSnapshot(rd.getSnapshot())).append('\n');
        }
        if (!rd.getCommittedEntries().isEmpty()) {
            buf.append("CommittedEntries:\n");
            buf.append(describeEntries(rd.getCommittedEntries(), f));
        }
        if (!rd.getMessages().isEmpty()) {
            buf.append("Messages:\n");
            for (Message m : rd.getMessages()) {
                buf.append(describeMessage(m, f)).append('\n');
            }
        }
        if (buf.length() > 0) {
            return "Ready MustSync=" + rd.isMustSync() + ":\n" + buf;
        }
        return "<empty Ready>";
    }
}
