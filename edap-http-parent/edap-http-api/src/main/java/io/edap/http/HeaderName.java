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

import io.edap.http.decoder.TokenDecoder;

/**
 * 定义http协议HeaderName的对象，Http HeaderName对象尽量采用缓存的方式减少String的生成
 */
public class HeaderName {

    /**
     * HeaderName的名称
     */
    public final String name;
    /**
     * 该HeaderName写入网络时的byte数组，增加":" 以及空格
     */
    public final byte[] writeBytes;
    /**
     * HeaderName的byte数组方便进行与网络数据进行严格比对
     */
    public final byte[] bytes;
    /**
     * 该header的值使用的HeaderValue解析器对象，如果未指定则使用HeaderValueDecoder该解码器不使用缓存
     */
    public TokenDecoder<HeaderValue> valueDecoder;
    /**
     * 是否header已经结束
     */
    public boolean finish;

    public HeaderName(final String name) {
        this.name  = name;
        this.bytes = name.getBytes();

        int    len = bytes.length;
        byte[] wbs = new byte[len+2];

        System.arraycopy(bytes, 0, wbs, 0, len);
        wbs[len++] = ':';
        wbs[len]   = ' ';

        this.writeBytes = name.getBytes();
    }
}
