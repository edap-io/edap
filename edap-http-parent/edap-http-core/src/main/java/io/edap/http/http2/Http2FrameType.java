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
 * HTTP/2 帧类型（RFC 9113 §4）。
 *
 * <p>未知帧类型必须被忽略（RFC 9113 §4.1），因此这里只声明协议已定义的类型码，
 * 不做成 enum —— 未知类型码在解码时走「忽略」分支而不是抛异常。</p>
 */
public final class Http2FrameType {

    public static final int DATA          = 0x0;
    public static final int HEADERS       = 0x1;
    public static final int PRIORITY      = 0x2;
    public static final int RST_STREAM    = 0x3;
    public static final int SETTINGS      = 0x4;
    public static final int PUSH_PROMISE  = 0x5;
    public static final int PING          = 0x6;
    public static final int GOAWAY        = 0x7;
    public static final int WINDOW_UPDATE = 0x8;
    public static final int CONTINUATION  = 0x9;

    private Http2FrameType() {
    }

    public static String name(int type) {
        switch (type) {
            case DATA:          return "DATA";
            case HEADERS:       return "HEADERS";
            case PRIORITY:      return "PRIORITY";
            case RST_STREAM:    return "RST_STREAM";
            case SETTINGS:      return "SETTINGS";
            case PUSH_PROMISE:  return "PUSH_PROMISE";
            case PING:          return "PING";
            case GOAWAY:        return "GOAWAY";
            case WINDOW_UPDATE: return "WINDOW_UPDATE";
            case CONTINUATION:  return "CONTINUATION";
            default:            return "UNKNOWN(" + type + ")";
        }
    }
}
