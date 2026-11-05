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

import java.util.ArrayList;
import java.util.List;

/**
 * HTTP/2 SETTINGS 参数（RFC 9113 §6.5）。
 *
 * <p>一个连接持有两份实例：<b>local</b>（本端要发给对端的）和 <b>remote</b>（对端发给本端的）。
 * {@link #apply(byte[], int, int)} 解析对端的 SETTINGS 载荷并就地更新；
 * {@link #encode()} 把本端设置序列化成 SETTINGS 载荷。</p>
 *
 * <p>{@code INITIAL_WINDOW_SIZE} 变更时，连接层必须把差值传播给所有已存在的流
 * （RFC 9113 §6.9.2），这属于连接层的职责，本类只负责解析与比较。</p>
 */
public class Http2Settings {

    public static final int HEADER_TABLE_SIZE      = 0x1;
    public static final int ENABLE_PUSH            = 0x2;
    public static final int MAX_CONCURRENT_STREAMS = 0x3;
    public static final int INITIAL_WINDOW_SIZE    = 0x4;
    public static final int MAX_FRAME_SIZE         = 0x5;
    public static final int MAX_HEADER_LIST_SIZE   = 0x6;

    /** RFC 9113 §6.5.2：SETTINGS_INITIAL_WINDOW_SIZE 的取值范围 */
    public static final int MAX_WINDOW_SIZE = 0x7fffffff;
    /** RFC 9113 §6.5.2：SETTINGS_MAX_FRAME_SIZE 的取值范围 */
    public static final int MIN_FRAME_SIZE = 16384;
    public static final int MAX_FRAME_SIZE_LIMIT = 16777215;

    public static final int DEFAULT_HEADER_TABLE_SIZE = 4096;
    public static final int DEFAULT_INITIAL_WINDOW_SIZE = 65535;
    public static final int DEFAULT_MAX_FRAME_SIZE = 16384;
    /** RFC 9113 §6.5.2：MAX_HEADER_LIST_SIZE 缺省为无限；这里取一个有限值兜底，避免无界分配 */
    public static final int DEFAULT_MAX_HEADER_LIST_SIZE = 65536;

    private int  headerTableSize      = DEFAULT_HEADER_TABLE_SIZE;
    private int  enablePush           = 0;
    private int  maxConcurrentStreams = Integer.MAX_VALUE;
    private int  initialWindowSize    = DEFAULT_INITIAL_WINDOW_SIZE;
    private int  maxFrameSize         = DEFAULT_MAX_FRAME_SIZE;
    private int  maxHeaderListSize    = DEFAULT_MAX_HEADER_LIST_SIZE;

    /**
     * 解析对端发来的 SETTINGS 载荷并就地更新。
     *
     * @param payload SETTINGS 帧载荷（6 字节一组：2 字节标识 + 4 字节值）
     * @param offset  载荷起始偏移
     * @param length  载荷长度，必须是 6 的整数倍
     * @throws Http2Exception 载荷长度非法或参数取值越界（FRAME_SIZE_ERROR / PROTOCOL_ERROR）
     */
    public void apply(byte[] payload, int offset, int length) {
        if (length % 6 != 0) {
            throw new Http2Exception(Http2ErrorCode.FRAME_SIZE_ERROR,
                    "SETTINGS 载荷长度必须是 6 的整数倍，实际 " + length);
        }
        int end = offset + length;
        int p = offset;
        while (p < end) {
            int id = ((payload[p] & 0xff) << 8) | (payload[p + 1] & 0xff);
            long value = 0;
            for (int i = 0; i < 4; i++) {
                value = (value << 8) | (payload[p + 2 + i] & 0xffL);
            }
            p += 6;
            switch (id) {
                case HEADER_TABLE_SIZE:
                    headerTableSize = (int) value;
                    break;
                case ENABLE_PUSH:
                    // RFC 9113 §6.5.2：取值只能是 0 或 1，其它值是 PROTOCOL_ERROR
                    if (value != 0 && value != 1) {
                        throw new Http2Exception(Http2ErrorCode.PROTOCOL_ERROR,
                                "SETTINGS_ENABLE_PUSH 取值非法: " + value);
                    }
                    enablePush = (int) value;
                    break;
                case MAX_CONCURRENT_STREAMS:
                    maxConcurrentStreams = (int) Math.min(value, Integer.MAX_VALUE);
                    break;
                case INITIAL_WINDOW_SIZE:
                    if (value > MAX_WINDOW_SIZE) {
                        throw new Http2Exception(Http2ErrorCode.FLOW_CONTROL_ERROR,
                                "SETTINGS_INITIAL_WINDOW_SIZE 越界: " + value);
                    }
                    initialWindowSize = (int) value;
                    break;
                case MAX_FRAME_SIZE:
                    if (value < MIN_FRAME_SIZE || value > MAX_FRAME_SIZE_LIMIT) {
                        throw new Http2Exception(Http2ErrorCode.PROTOCOL_ERROR,
                                "SETTINGS_MAX_FRAME_SIZE 越界: " + value);
                    }
                    maxFrameSize = (int) value;
                    break;
                case MAX_HEADER_LIST_SIZE:
                    maxHeaderListSize = (int) Math.min(value, Integer.MAX_VALUE);
                    break;
                default:
                    // RFC 9113 §6.5.2：未知参数必须被忽略
                    break;
            }
        }
    }

    /**
     * 序列化本端设置为 SETTINGS 载荷。只输出与默认值不同的参数（RFC 9113 §6.5）。
     */
    public byte[] encode() {
        List<byte[]> entries = new ArrayList<>(6);
        if (headerTableSize != DEFAULT_HEADER_TABLE_SIZE) {
            entries.add(entry(HEADER_TABLE_SIZE, headerTableSize));
        }
        if (enablePush != 0) {
            entries.add(entry(ENABLE_PUSH, enablePush));
        }
        if (maxConcurrentStreams != Integer.MAX_VALUE) {
            entries.add(entry(MAX_CONCURRENT_STREAMS, maxConcurrentStreams));
        }
        if (initialWindowSize != DEFAULT_INITIAL_WINDOW_SIZE) {
            entries.add(entry(INITIAL_WINDOW_SIZE, initialWindowSize));
        }
        if (maxFrameSize != DEFAULT_MAX_FRAME_SIZE) {
            entries.add(entry(MAX_FRAME_SIZE, maxFrameSize));
        }
        if (maxHeaderListSize != DEFAULT_MAX_HEADER_LIST_SIZE) {
            entries.add(entry(MAX_HEADER_LIST_SIZE, maxHeaderListSize));
        }
        byte[] out = new byte[entries.size() * 6];
        int p = 0;
        for (byte[] e : entries) {
            System.arraycopy(e, 0, out, p, 6);
            p += 6;
        }
        return out;
    }

    private static byte[] entry(int id, int value) {
        byte[] e = new byte[6];
        e[0] = (byte) (id >>> 8);
        e[1] = (byte) id;
        e[2] = (byte) (value >>> 24);
        e[3] = (byte) (value >>> 16);
        e[4] = (byte) (value >>> 8);
        e[5] = (byte) value;
        return e;
    }

    public int getHeaderTableSize() {
        return headerTableSize;
    }

    public void setHeaderTableSize(int headerTableSize) {
        this.headerTableSize = headerTableSize;
    }

    public int getEnablePush() {
        return enablePush;
    }

    public void setEnablePush(int enablePush) {
        this.enablePush = enablePush;
    }

    public int getMaxConcurrentStreams() {
        return maxConcurrentStreams;
    }

    public void setMaxConcurrentStreams(int maxConcurrentStreams) {
        this.maxConcurrentStreams = maxConcurrentStreams;
    }

    public int getInitialWindowSize() {
        return initialWindowSize;
    }

    public void setInitialWindowSize(int initialWindowSize) {
        this.initialWindowSize = initialWindowSize;
    }

    public int getMaxFrameSize() {
        return maxFrameSize;
    }

    public void setMaxFrameSize(int maxFrameSize) {
        this.maxFrameSize = maxFrameSize;
    }

    public int getMaxHeaderListSize() {
        return maxHeaderListSize;
    }

    public void setMaxHeaderListSize(int maxHeaderListSize) {
        this.maxHeaderListSize = maxHeaderListSize;
    }
}
