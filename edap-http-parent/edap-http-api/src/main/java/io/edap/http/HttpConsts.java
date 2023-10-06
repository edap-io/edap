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

import io.edap.http.header.ContentLength;
import io.edap.http.header.Header;
import io.edap.http.header.HeaderDate;
import io.edap.http.header.HeaderServer;

import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

public class HttpConsts {

    /**
     * 预置的header名称，预置header由框架来进行设置，用户设置无效
     */
    public static Map<String, Header> BUILDIN_HEADERS;

    public static Charset DEFAULT_CHARSET = Charset.forName("utf-8");

    public static int[] BYTE_VALUES = new int[71];

    static {

        Arrays.fill(BYTE_VALUES, -1);
        int v = 0;
        for (int i=48;i<58;i++) {
            BYTE_VALUES[i] = v++;
        }
        v = 10;
        for (int i=65;i<71;i++) {
            BYTE_VALUES[i] = v++;
        }

        BUILDIN_HEADERS = new HashMap<>();
        BUILDIN_HEADERS.put("Server", new HeaderServer());
        BUILDIN_HEADERS.put("Content-Length", new ContentLength());
        BUILDIN_HEADERS.put("Date", new HeaderDate());
    }

    private HttpConsts() {}
}
