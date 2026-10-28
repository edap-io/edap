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

import io.edap.raft.codec.ByteReader;
import io.edap.raft.codec.ByteWriter;
import io.edap.raft.RaftException;

import java.util.ArrayList;
import java.util.List;

/**
 * 成员变更的序列化。
 *
 * <p>与 etcd 的 {@code raftpb.MarshalConfChange} 对应：返回条目类型与编码后的数据。
 * v1 与 v2 的编码格式不同，靠条目类型区分。</p>
 *
 * <p>布局（全部大端）：</p>
 * <pre>
 * v1: id(8) type(1) nodeId(8) contextLen(4) context
 * v2: transition(1) changesCount(4) [type(1) nodeId(8)]* contextLen(4) context
 * </pre>
 */
public final class ConfChangeCodec {

    private ConfChangeCodec() {
    }

    /**
     * 把成员变更编码成日志条目。返回条目类型与编码后的数据。
     *
     * @param change 成员变更，null 表示"空的 v2 变更"
     */
    public static EntryTypeAndData marshal(ConfChangeI change) {
        if (change == null) {
            // 空变更编码成空的 v2，appendEntry 不会因为它的大小拒绝它
            return new EntryTypeAndData(EntryType.CONFIG_CHANGE_V2, new byte[0]);
        }
        ConfChange v1 = change.asV1();
        if (v1 != null) {
            ByteWriter writer = new ByteWriter(32);
            writer.writeLong(v1.getId());
            writer.writeByte(v1.getType().getCode());
            writer.writeLong(v1.getNodeId());
            writer.writeInt(v1.getContext().length);
            writer.writeBytes(v1.getContext());
            return new EntryTypeAndData(EntryType.CONFIG_CHANGE, writer.toByteArray());
        }
        ConfChangeV2 v2 = change.asV2();
        ByteWriter writer = new ByteWriter(32);
        writer.writeByte(v2.getTransition().getCode());
        writer.writeInt(v2.getChanges().size());
        for (ConfChangeSingle single : v2.getChanges()) {
            writer.writeByte(single.getType().getCode());
            writer.writeLong(single.getNodeId());
        }
        writer.writeInt(v2.getContext().length);
        writer.writeBytes(v2.getContext());
        return new EntryTypeAndData(EntryType.CONFIG_CHANGE_V2, writer.toByteArray());
    }

    /**
     * 按条目类型解码成员变更
     */
    public static ConfChangeI unmarshal(EntryType type, byte[] data) {
        if (type == EntryType.CONFIG_CHANGE) {
            if (data == null || data.length == 0) {
                // 空的 v1 变更：etcd 的零值 ConfChange，转成 v2 后是一条
                // 「把 0 号节点设为投票成员」的变更，不是「空变更」
                return new ConfChange(ConfChangeType.ADD_NODE, 0, new byte[0]);
            }
            ByteReader reader = new ByteReader(data);
            long id = reader.readLong();
            ConfChangeType changeType = ConfChangeType.of((byte) reader.readUnsignedByte());
            long nodeId = reader.readLong();
            byte[] context = reader.readBytes(reader.readInt());
            return new ConfChange(changeType, nodeId, context, id);
        }
        if (data == null || data.length == 0) {
            // 空的 v2 变更
            return new ConfChangeV2(ConfChangeTransition.AUTO, new ArrayList<>(), new byte[0]);
        }
        ByteReader reader = new ByteReader(data);
        ConfChangeTransition transition = ConfChangeTransition.of((byte) reader.readUnsignedByte());
        int count = reader.readInt();
        List<ConfChangeSingle> changes = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            ConfChangeType changeType = ConfChangeType.of((byte) reader.readUnsignedByte());
            long nodeId = reader.readLong();
            changes.add(new ConfChangeSingle(changeType, nodeId));
        }
        byte[] context = reader.readBytes(reader.readInt());
        return new ConfChangeV2(transition, changes, context);
    }

    /**
     * 把 "vn / ln / rn / un" 这样的字符串解析成变更列表，与 etcd 的
     * {@code raftpb.ConfChangesFromString} 对齐。
     */
    public static List<ConfChangeSingle> changesFromString(String s) {
        List<ConfChangeSingle> result = new ArrayList<>();
        if (s == null) {
            return result;
        }
        String trimmed = s.trim();
        if (trimmed.isEmpty()) {
            return result;
        }
        for (String token : trimmed.split(" ")) {
            if (token.length() < 2) {
                throw new RaftException("unknown token " + token);
            }
            ConfChangeType type;
            switch (token.charAt(0)) {
                case 'v':
                    type = ConfChangeType.ADD_NODE;
                    break;
                case 'l':
                    type = ConfChangeType.ADD_LEARNER_NODE;
                    break;
                case 'r':
                    type = ConfChangeType.REMOVE_NODE;
                    break;
                case 'u':
                    type = ConfChangeType.UPDATE_NODE;
                    break;
                default:
                    throw new RaftException("unknown input: " + token);
            }
            long nodeId;
            try {
                nodeId = Long.parseLong(token.substring(1));
            } catch (NumberFormatException e) {
                throw new RaftException("unknown token " + token);
            }
            result.add(new ConfChangeSingle(type, nodeId));
        }
        return result;
    }

    /**
     * {@link #changesFromString(String)} 的逆操作
     */
    public static String changesToString(List<ConfChangeSingle> changes) {
        StringBuilder buf = new StringBuilder();
        for (int i = 0; i < changes.size(); i++) {
            if (i > 0) {
                buf.append(' ');
            }
            ConfChangeSingle single = changes.get(i);
            switch (single.getType()) {
                case ADD_NODE:
                    buf.append('v');
                    break;
                case ADD_LEARNER_NODE:
                    buf.append('l');
                    break;
                case REMOVE_NODE:
                    buf.append('r');
                    break;
                case UPDATE_NODE:
                    buf.append('u');
                    break;
                default:
                    buf.append("unknown");
                    break;
            }
            buf.append(single.getNodeId());
        }
        return buf.toString();
    }

    /**
     * 条目类型 + 编码后的数据
     */
    public static class EntryTypeAndData {
        private final EntryType type;
        private final byte[]   data;

        public EntryTypeAndData(EntryType type, byte[] data) {
            this.type = type;
            this.data = data;
        }

        public EntryType getType() {
            return type;
        }

        public byte[] getData() {
            return data;
        }
    }
}
