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

package io.edap.http.decoder;

import io.edap.buffer.FastBuf;
import io.edap.codec.FastBufDataRange;
import io.edap.http.HeaderValue;
import io.edap.http.HttpRequest;
import io.edap.http.headervalue.ContentTypeValue;

/**
 * @author : luysh@yonyou.com
 * @date : 2020/12/4
 */
public class ContentTypeValueDecoder extends HeaderValueCacheDecoder {

    @Override
    public HeaderValue decode(FastBuf buf, FastBufDataRange dataRange, HttpRequest request) {

        HeaderValue hv = super.decode(buf, dataRange, request);
        if (hv != null) {
            ContentTypeValue htv = ContentTypeValue.fromHeaderValue(hv);
            CACHE.set(hv);

            return htv;
        }
        return null;
    }
}
