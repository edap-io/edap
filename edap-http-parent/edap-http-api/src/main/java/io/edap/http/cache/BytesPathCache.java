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
import io.edap.http.HttpHandler;
import io.edap.http.PathInfo;
import io.edap.http.handler.NotFoundHandler;

import java.util.HashMap;
import java.util.Map;

public class BytesPathCache {

    /**
     * 路径的缓存方便解析path，并获取HttpHandler数组的下标
     */
    private Map<BytesDataRange, PathInfo> pathCache;

    public static PathInfo NOT_FOUND_PATH;
    static {
        NOT_FOUND_PATH = new PathInfo();
        NOT_FOUND_PATH.setFound(false);
        NOT_FOUND_PATH.setHttpHandlers(new HttpHandler[]{new NotFoundHandler()});
    }

    private BytesPathCache() {
        pathCache = new HashMap<>();
    }

    public PathInfo get(BytesDataRange dataRange) {
        PathInfo pi = pathCache.get(dataRange);
        if (pi != null) {
            return pi;
        }
        return NOT_FOUND_PATH;
    }

    /**
     * 向系统中注册一个路径和HttpHandler的对应关系
     * @param path
     * @param handler
     */
    public synchronized void registerHandler(String path, HttpHandler handler, String... methods) {
        BytesDataRange key = BytesDataRange.from(path);
        PathInfo pathInfo = pathCache.get(key);
        MethodCache methodCache = MethodCache.instance();
        if (pathInfo == null) {
            pathInfo = new PathInfo();
            pathInfo.setPath(path);
            pathInfo.setFound(true);
            HttpHandler[] handlers = new HttpHandler[16];
            for (int i=0;i<methods.length;i++) {
                int methodIndex = methodCache.getMethodIndex(methods[i]);
                if (methodIndex > handlers.length - 1) {
                    handlers = new HttpHandler[methodIndex+1];
                }
                handlers[methodIndex] = handler;
            }
            pathInfo.setHttpHandlers(handlers);
            pathCache.put(key, pathInfo);
        } else {
            HttpHandler[] handlers = pathInfo.getHttpHandlers();
            for (int i=0;i<methods.length;i++) {
                int methodIndex = methodCache.getMethodIndex(methods[i]);
                if (methodIndex > handlers.length - 1) {
                    handlers = new HttpHandler[methodIndex+1];
                }
                handlers[methodIndex] = handler;
            }
        }

    }

    public static final BytesPathCache instance() {
        return SingletonHolder.INSTANCE;
    }

    private static class SingletonHolder {
        private static final BytesPathCache INSTANCE = new BytesPathCache();
    }
}
