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

import io.edap.raft.RaftException;
import io.edap.raft.codec.ByteReader;
import io.edap.raft.codec.ByteWriter;

import java.util.ArrayList;
import java.util.List;

/**
 * Raft 消息的编解码。
 *
 * <p>帧格式（全部大端）：</p>
 * <pre>
 * +0  length (4)  消息体长度，不含 length 本身
 * +4  type   (1)  MessageType
 * +5  payload
 * </pre>
 *
 * <p>payload 采用<b>扁平布局</b>，与 {@link Message} 的字段一一对应，
 * 不按消息类型分支。这样新增消息类型时不必改线格式。</p>
 *
 * <pre>
 * to(8) from(8) term(8) logTerm(8) index(8) commit(8) vote(8)
 * reject(1) rejectHint(8)
 * entriesCount(4) + entries
 * snapshotPresent(1) + snapshot
 * contextLen(4) + context
 * responsesCount(4) + responses
 * </pre>
 *
 * <p>{@code contextLen} 为 -1 表示没有附加数据，为 0 表示附加数据是空的，
 * 两者区分开，编解码之后消息仍可按值比较。</p>
 */
public class RaftCodec {

    /** 长度字段的字节数 */
    public static final int LENGTH_FIELD_SIZE = 4;
    /** 类型字段的字节数 */
    public static final int TYPE_FIELD_SIZE = 1;
    /** 帧头总长度 */
    public static final int HEADER_SIZE = LENGTH_FIELD_SIZE + TYPE_FIELD_SIZE;

    private RaftCodec() {
    }

    /**
     * 把消息编码成完整的帧
     */
    public static byte[] encode(Message msg) {
        ByteWriter payload = new ByteWriter(128);
        encodePayload(payload, msg);
        byte[] body = payload.toByteArray();

        ByteWriter frame = new ByteWriter(HEADER_SIZE + body.length);
        frame.writeInt(body.length);
        frame.writeByte(msg.getType().getCode());
        frame.writeBytes(body);
        return frame.toByteArray();
    }

    /**
     * 从缓冲区中读出帧的消息体长度。
     *
     * @return 消息体长度；缓冲区里还不足 4 字节时返回 -1
     */
    public static int frameLength(byte[] buf, int offset) {
        if (buf.length - offset < LENGTH_FIELD_SIZE) {
            return -1;
        }
        int length = ((buf[offset] & 0xFF) << 24)
                | ((buf[offset + 1] & 0xFF) << 16)
                | ((buf[offset + 2] & 0xFF) << 8)
                | (buf[offset + 3] & 0xFF);
        if (length < 0) {
            throw new RaftException("Illegal raft frame length: " + length);
        }
        return length;
    }

    /**
     * 解码一个完整的帧
     *
     * @param buf    帧所在的缓冲区
     * @param offset 帧起始位置
     */
    public static Message decodeFrame(byte[] buf, int offset) {
        int length = frameLength(buf, offset);
        if (length < 0) {
            throw new RaftException("Raft frame is incomplete");
        }
        int typeOffset = offset + LENGTH_FIELD_SIZE;
        if (buf.length - typeOffset < TYPE_FIELD_SIZE + length) {
            throw new RaftException("Raft frame is incomplete");
        }
        MessageType type = MessageType.of((byte) (buf[typeOffset] & 0xFF));
        byte[] body = new byte[length];
        System.arraycopy(buf, typeOffset + TYPE_FIELD_SIZE, body, 0, length);
        Message msg = decodePayload(body, type);
        return msg;
    }

    private static void encodePayload(ByteWriter writer, Message msg) {
        writer.writeLong(msg.getTo());
        writer.writeLong(msg.getFrom());
        writer.writeLong(msg.getTerm());
        writer.writeLong(msg.getLogTerm());
        writer.writeLong(msg.getIndex());
        writer.writeLong(msg.getCommit());
        writer.writeLong(msg.getVote());
        writer.writeBoolean(msg.getReject());
        writer.writeLong(msg.getRejectHint());

        List<Entry> entries = msg.getEntries();
        if (entries == null || entries.isEmpty()) {
            writer.writeInt(0);
        } else {
            writer.writeInt(entries.size());
            for (Entry entry : entries) {
                encodeEntry(writer, entry);
            }
        }

        Snapshot snapshot = msg.getSnapshot();
        if (snapshot == null) {
            writer.writeBoolean(false);
        } else {
            writer.writeBoolean(true);
            byte[] data = snapshot.getData();
            writer.writeInt(data == null ? 0 : data.length);
            writer.writeBytes(data);
            SnapshotMetadata metadata = snapshot.getMetadata();
            if (metadata == null) {
                writer.writeLong(0);
                writer.writeLong(0);
                writer.writeBoolean(false);
            } else {
                writer.writeLong(metadata.getIndex());
                writer.writeLong(metadata.getTerm());
                // 成员配置可能为空，必须单独用一个标志位区分「没有配置」与「空配置」
                ConfState cs = metadata.getConfState();
                if (cs == null) {
                    writer.writeBoolean(false);
                } else {
                    writer.writeBoolean(true);
                    encodeConfState(writer, cs);
                }
            }
        }

        byte[] context = msg.getContext();
        if (context == null) {
            // -1 表示「没有附加数据」，与「空附加数据」区分开，
            // 这样编解码之后消息仍然可以按值比较
            writer.writeInt(-1);
        } else {
            writer.writeInt(context.length);
            writer.writeBytes(context);
        }

        List<Message> responses = msg.getResponses();
        if (responses == null || responses.isEmpty()) {
            writer.writeInt(0);
        } else {
            writer.writeInt(responses.size());
            for (Message response : responses) {
                ByteWriter sub = new ByteWriter(64);
                encodePayload(sub, response);
                byte[] subBytes = sub.toByteArray();
                writer.writeByte(response.getType().getCode());
                writer.writeInt(subBytes.length);
                writer.writeBytes(subBytes);
            }
        }
    }

    /**
     * 解码消息体
     */
    public static Message decodePayload(byte[] payload, MessageType type) {
        ByteReader reader = new ByteReader(payload);
        Message msg = new Message();
        msg.setType(type);
        msg.setTo(reader.readLong());
        msg.setFrom(reader.readLong());
        msg.setTerm(reader.readLong());
        msg.setLogTerm(reader.readLong());
        msg.setIndex(reader.readLong());
        msg.setCommit(reader.readLong());
        msg.setVote(reader.readLong());
        msg.setReject(reader.readBoolean());
        msg.setRejectHint(reader.readLong());

        int entryCount = reader.readInt();
        if (entryCount > 0) {
            List<Entry> entries = new ArrayList<>(entryCount);
            for (int i = 0; i < entryCount; i++) {
                entries.add(decodeEntry(reader));
            }
            msg.setEntries(entries);
        }

        if (reader.readBoolean()) {
            byte[] data = reader.readBytes(reader.readInt());
            long index = reader.readLong();
            long term = reader.readLong();
            ConfState confState = reader.readBoolean() ? decodeConfState(reader) : null;
            msg.setSnapshot(new Snapshot(data, new SnapshotMetadata(confState, index, term)));
        }

        int contextLength = reader.readInt();
        msg.setContext(contextLength < 0 ? null : reader.readBytes(contextLength));

        int responseCount = reader.readInt();
        if (responseCount > 0) {
            List<Message> responses = new ArrayList<>(responseCount);
            for (int i = 0; i < responseCount; i++) {
                MessageType responseType = MessageType.of((byte) reader.readUnsignedByte());
                byte[] body = reader.readBytes(reader.readInt());
                responses.add(decodePayload(body, responseType));
            }
            msg.setResponses(responses);
        }
        return msg;
    }

    private static void encodeEntry(ByteWriter writer, Entry entry) {
        writer.writeLong(entry.getIndex());
        writer.writeLong(entry.getTerm());
        writer.writeByte(entry.getType().getCode());
        byte[] data = entry.getData();
        writer.writeInt(data == null ? 0 : data.length);
        writer.writeBytes(data);
    }

    /**
     * 单条日志编码后的字节数。
     *
     * <p>布局是 index(8) + term(8) + type(1) + dataLen(4) + data，
     * 与 {@link #encodeEntry(ByteWriter, Entry)} 一一对应。</p>
     */
    public static int entrySize(Entry entry) {
        byte[] data = entry.getData();
        return 8 + 8 + 1 + 4 + (data == null ? 0 : data.length);
    }

    /**
     * 一批日志编码后的总字节数，等价于 etcd 的 {@code entsSize}。
     */
    public static long entriesSize(List<Entry> entries) {
        if (entries == null || entries.isEmpty()) {
            return 0;
        }
        long size = 0;
        for (Entry entry : entries) {
            size += entrySize(entry);
        }
        return size;
    }

    private static Entry decodeEntry(ByteReader reader) {
        long index = reader.readLong();
        long term = reader.readLong();
        EntryType type = EntryType.of((byte) reader.readUnsignedByte());
        byte[] data = reader.readBytes(reader.readInt());
        return new Entry(index, term, type, data);
    }

    /**
     * 编码成员配置。调用方保证 {@code state} 非 null，
     * 「没有配置」由外层的标志位区分。
     */
    private static void encodeConfState(ByteWriter writer, ConfState state) {
        writeIds(writer, state.getVoters());
        writeIds(writer, state.getLearners());
        writeIds(writer, state.getVotersOutgoing());
        writeIds(writer, state.getLearnersNext());
        writer.writeBoolean(state.isAutoLeave());
    }

    private static void writeIds(ByteWriter writer, List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            writer.writeInt(0);
            return;
        }
        writer.writeInt(ids.size());
        for (Long id : ids) {
            writer.writeLong(id);
        }
    }

    private static ConfState decodeConfState(ByteReader reader) {
        List<Long> voters = readIds(reader);
        List<Long> learners = readIds(reader);
        List<Long> votersOutgoing = readIds(reader);
        List<Long> learnersNext = readIds(reader);
        boolean autoLeave = reader.readBoolean();
        return new ConfState(voters, learners, votersOutgoing, learnersNext, autoLeave);
    }

    private static List<Long> readIds(ByteReader reader) {
        int count = reader.readInt();
        if (count <= 0) {
            return new ArrayList<>();
        }
        List<Long> ids = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            ids.add(reader.readLong());
        }
        return ids;
    }
}
