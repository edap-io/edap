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

package io.edap.http.header;

import io.edap.util.ByteData;

import static io.edap.util.FastNum.uncheckWriteInt;

public class ContentLength extends Header {

    private static byte[] keyBytes = "Content-Length: ".getBytes();

    public static String NAME = "Content-Length";

    private static final ThreadLocal<ByteData> BYTE_CACHE_LOCAL = new ThreadLocal<ByteData>() {
        @Override
        protected ByteData initialValue() {
            ByteData data = new ByteData();
            byte[] bs = new byte[32];
            System.arraycopy(keyBytes, 0, bs, 0, keyBytes.length);
            data.setBytes(bs);
            data.setOffset(keyBytes.length);
            data.setOffset(0);
            return data;
        }
    };

    public ContentLength() {
        super("Content-Length", "");
    }

    public static ByteData getByteData(int length) {
        ByteData data = BYTE_CACHE_LOCAL.get();
        data.setOffset(keyBytes.length);
        uncheckWriteInt(data, length);
        data.getBytes()[data.getOffset()] = '\r';
        data.getBytes()[data.getOffset()+1] = '\n';
        data.setLength(data.getOffset()+2);
        data.setOffset(0);
        return data;
    }
}
