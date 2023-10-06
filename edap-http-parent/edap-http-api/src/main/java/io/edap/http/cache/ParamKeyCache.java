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

import java.util.HashMap;
import java.util.Map;

import static io.edap.util.Constants.EMPTY_STRING;

public class ParamKeyCache {

    private Map<FastBufDataRange, String> cache;

    private ParamKeyCache() {
        cache = new HashMap<>(128);
    }

    public String get(FastBufDataRange dataRange) {
        if (dataRange.length() <= 0) {
            return EMPTY_STRING;
        }
        String name = cache.get(dataRange);
        if (name == null) {
            name = dataRange.getString();
            cache.put(FastBufDataRange.from(name), dataRange.getString());
        }
        return name;
    }

    public static final ParamKeyCache instance() {
        return SingletonHolder.INSTANCE;
    }

    private static class SingletonHolder {
        private static final ParamKeyCache INSTANCE = new ParamKeyCache();
    }
}
