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

import io.edap.nio.codec.FastBufDataRange;
import io.edap.http.HeaderName;

import java.util.HashMap;
import java.util.Map;

public class HeaderNameCache {

    private Map<FastBufDataRange, HeaderName> cache;
    private Map<String, HeaderName>           stringCache;

    private HeaderNameCache() {
        cache       = new HashMap<>(16);
        stringCache = new HashMap<>(16);
    }

    public HeaderName get(FastBufDataRange dataRange) {
        HeaderName hn = cache.get(dataRange);
        //long start = System.nanoTime();
        if (hn == null) {
            hn = new HeaderName(dataRange.getString());
            cache.put(FastBufDataRange.from(hn.name), hn);
        }
        //System.out.println("time=" + (System.nanoTime() - start));
        return hn;
    }

    public HeaderName get(String name) {
        HeaderName hn = stringCache.get(name);
        if (hn == null) {
            hn = new HeaderName(name);
            stringCache.put(name, hn);
        }
        return hn;
    }

    public static final HeaderNameCache instance() {
        return SingletonHolder.INSTANCE;
    }

    private static class SingletonHolder {
        private static final HeaderNameCache INSTANCE = new HeaderNameCache();
    }
}
