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

package io.edap.http;

public enum HttpVersion {
    HTTP_0_9("HTTP/0.9",  9),
    HTTP_1_0("HTTP/1.0", 10),
    HTTP_1_1("HTTP/1.1", 11),
    HTTP_2_0("HTTP/2.0", 20),

    NOT_SUPPORT_VERSION("HTTP/UNKNOWN", -1);

    private final String string;
    private final byte[] bytes;
    private final int    version;

    HttpVersion(String s, int version) {
        this.string  = s;
        this.bytes   = (s + " ").getBytes();
        this.version = version;
    }

    public byte[] bytes() {
        return bytes;
    }

    public int version() {
        return version;
    }

    public String string() {
        return string;
    }

    @Override
    public String toString() {
        return string;
    }

    public static HttpVersion fromVersion(String version) {
        switch (version) {
            case "HTTP/0.9": return HttpVersion.HTTP_0_9;
            case "HTTP/1.0": return HttpVersion.HTTP_1_0;
            case "HTTP/1.1": return HttpVersion.HTTP_1_1;
            case "HTTP/2.0": return HttpVersion.HTTP_2_0;
            default: throw new IllegalArgumentException();
        }
    }

    public static HttpVersion fromVersion(int version) {
        switch(version) {
            case  9: return HttpVersion.HTTP_0_9;
            case 10: return HttpVersion.HTTP_1_0;
            case 11: return HttpVersion.HTTP_1_1;
            case 20: return HttpVersion.HTTP_2_0;
            default: throw new IllegalArgumentException();
        }
    }
}
