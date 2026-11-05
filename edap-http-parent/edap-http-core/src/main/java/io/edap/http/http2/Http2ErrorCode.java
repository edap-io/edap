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
 * HTTP/2 错误码（RFC 9113 §7）。
 */
public final class Http2ErrorCode {

    public static final int NO_ERROR            = 0x0;
    public static final int PROTOCOL_ERROR      = 0x1;
    public static final int INTERNAL_ERROR      = 0x2;
    public static final int FLOW_CONTROL_ERROR = 0x3;
    public static final int SETTINGS_TIMEOUT    = 0x4;
    public static final int STREAM_CLOSED      = 0x5;
    public static final int FRAME_SIZE_ERROR    = 0x6;
    public static final int REFUSED_STREAM      = 0x7;
    public static final int CANCEL              = 0x8;
    public static final int COMPRESSION_ERROR   = 0x9;
    public static final int CONNECT_ERROR      = 0xa;
    public static final int ENHANCE_YOUR_CALM  = 0xb;
    public static final int INADEQUATE_SECURITY= 0xc;
    public static final int HTTP_1_1_REQUIRED  = 0xd;

    private Http2ErrorCode() {
    }

    public static String name(int code) {
        switch (code) {
            case NO_ERROR:             return "NO_ERROR";
            case PROTOCOL_ERROR:       return "PROTOCOL_ERROR";
            case INTERNAL_ERROR:       return "INTERNAL_ERROR";
            case FLOW_CONTROL_ERROR:  return "FLOW_CONTROL_ERROR";
            case SETTINGS_TIMEOUT:     return "SETTINGS_TIMEOUT";
            case STREAM_CLOSED:        return "STREAM_CLOSED";
            case FRAME_SIZE_ERROR:     return "FRAME_SIZE_ERROR";
            case REFUSED_STREAM:       return "REFUSED_STREAM";
            case CANCEL:               return "CANCEL";
            case COMPRESSION_ERROR:    return "COMPRESSION_ERROR";
            case CONNECT_ERROR:        return "CONNECT_ERROR";
            case ENHANCE_YOUR_CALM:    return "ENHANCE_YOUR_CALM";
            case INADEQUATE_SECURITY:  return "INADEQUATE_SECURITY";
            case HTTP_1_1_REQUIRED:    return "HTTP_1_1_REQUIRED";
            default:                   return "UNKNOWN(" + code + ")";
        }
    }
}
