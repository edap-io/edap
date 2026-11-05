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
 * HTTP/2 帧标志位（RFC 9113 §6）。
 *
 * <p>同一个标志位在不同帧类型下含义不同（例如 ACK 对 SETTINGS / PING 都是 0x1，
 * END_STREAM 只对 DATA / HEADERS 有意义），所以这里只是常量集合，不做位组合封装。</p>
 */
public final class Http2Flags {

    /** DATA / HEADERS：流的最后一部分 */
    public static final int END_STREAM  = 0x1;
    /** SETTINGS / PING：确认应答 */
    public static final int ACK         = 0x1;
    /** HEADERS / CONTINUATION：header block 结束 */
    public static final int END_HEADERS = 0x4;
    /** DATA / HEADERS：带 padding */
    public static final int PADDED      = 0x8;
    /** HEADERS：带优先级字段 */
    public static final int PRIORITY    = 0x20;

    private Http2Flags() {
    }

    public static boolean isSet(int flags, int flag) {
        return (flags & flag) != 0;
    }
}
