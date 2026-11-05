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

package io.edap.http.http2;

/**
 * HTTP/2 帧头（RFC 9113 §4.1）。
 *
 * <pre>
 * +-----------------------------------------------+
 * |                 Length (24)                   |
 * +---------------+---------------+---------------+
 * |   Type (8)    |   Flags (8)   |
 * +-+-------------+---------------+-------------------------------+
 * |R|                 Stream Identifier (31)                      |
 * +=+=============================================================+
 * </pre>
 */
public class Http2FrameHeader {

    private final int length;
    private final int type;
    private final int flags;
    private final int streamId;

    public Http2FrameHeader(int length, int type, int flags, int streamId) {
        this.length   = length;
        this.type     = type;
        this.flags    = flags;
        this.streamId = streamId;
    }

    /** 载荷长度（不含 9 字节帧头） */
    public int getLength() {
        return length;
    }

    public int getType() {
        return type;
    }

    public int getFlags() {
        return flags;
    }

    /** 流标识符（31 位，保留位已屏蔽） */
    public int getStreamId() {
        return streamId;
    }

    public boolean hasFlag(int flag) {
        return (flags & flag) != 0;
    }

    @Override
    public String toString() {
        return "Http2FrameHeader{type=" + Http2FrameType.name(type)
                + ", flags=0x" + Integer.toHexString(flags)
                + ", streamId=" + streamId
                + ", length=" + length + '}';
    }
}
