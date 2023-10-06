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

package io.edap.http.cache;

import io.edap.codec.FastBufDataRange;
import io.edap.http.HeaderValue;
import io.edap.util.StringUtil;

import java.util.HashMap;
import java.util.Map;

/**
 */
public class HeaderValueCache {

    private Map<FastBufDataRange, HeaderValue> cache;

    private HeaderValueCache() {
        cache = new HashMap<>(16);
    }

    public HeaderValue get(FastBufDataRange dataRange) {
        HeaderValue hn = cache.get(dataRange);
        if (hn == null) {
            String v = dataRange.getString();
            hn = new HeaderValue(v);
            cache.put(FastBufDataRange.from(v), hn);
        }
        return hn;
    }

    public void set(HeaderValue hv) {
        if (hv == null || StringUtil.isEmpty(hv.getValue())) {
            return;
        }
        cache.put(FastBufDataRange.from(hv.getValue()), hv);
    }

    public static final HeaderValueCache instance() {
        return SingletonHolder.INSTANCE;
    }

    private static class SingletonHolder {
        private static final HeaderValueCache INSTANCE = new HeaderValueCache();
    }
}
