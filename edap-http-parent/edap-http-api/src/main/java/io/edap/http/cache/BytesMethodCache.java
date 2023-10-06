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

import io.edap.codec.BytesDataRange;
import io.edap.http.MethodInfo;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class BytesMethodCache {

    private List<String>            methods;
    private Map<String, MethodInfo> cache;
    private Map<BytesDataRange, MethodInfo> rangeCache;

    private BytesMethodCache() {
        methods     = new ArrayList<>();
        cache       = new HashMap<>();
        rangeCache  = new HashMap<>();
    }

    public MethodInfo getMethodInfo(String method) {
        MethodInfo m = cache.get(method);
        if (m == null) {
            synchronized (this) {
                int index = getMethodIndex(method);
                m = new MethodInfo();
                m.setMethod(method);
                m.setMethodIndex(index);
                cache.put(method, m);
            }
        }
        return m;
    }

    public MethodInfo getMethodInfo(BytesDataRange dataRange) {
        MethodInfo m = rangeCache.get(dataRange);
        if (m == null) {
            synchronized (this) {
                String method = dataRange.getString();
                int index = getMethodIndex(method);
                m = new MethodInfo();
                m.setMethod(method);
                m.setMethodIndex(index);
                rangeCache.put(BytesDataRange.from(method), m);
            }
        }
        return m;
    }

    public synchronized int getMethodIndex(String method) {
        int index = methods.indexOf(method);
        if (index == -1) {
            index = methods.size();
            methods.add(method);
        }
        return index;
    }

    public static final BytesMethodCache instance() {
        return SingletonHolder.INSTANCE;
    }

    private static class SingletonHolder {
        private static final BytesMethodCache INSTANCE = new BytesMethodCache();
    }
}
