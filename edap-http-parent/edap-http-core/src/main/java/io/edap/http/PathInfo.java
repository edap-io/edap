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

/**
 * http路径信息
 */
public class PathInfo {
    /**
     * http的路径信息
     */
    private String  path;
    private String  matchPath;
    private boolean found;
    /**
     * 支持的method下标的列表
     */
    private HttpHandler[] httpHandlers;

    private HttpHandleOption handlerOption;
    /**
     * WebSocket协议处理器
     */
    private WSHandler wsHandler;

    /**
     * http的路径信息
     */
    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }


    /**
     * 支持的method下标的列表
     */
    public HttpHandler[] getHttpHandlers() {
        return httpHandlers;
    }

    public void setHttpHandlers(HttpHandler[] httpHandlers) {
        this.httpHandlers = httpHandlers;
    }

    public boolean isFound() {
        return found;
    }

    public void setFound(boolean found) {
        this.found = found;
    }

    public String getMatchPath() {
        return matchPath;
    }

    public void setMatchPath(String matchPath) {
        this.matchPath = matchPath;
    }

    public HttpHandleOption getHandlerOption() {
        return handlerOption;
    }

    public void setHandlerOption(HttpHandleOption handlerOption) {
        this.handlerOption = handlerOption;
    }

    /**
     * WebSocket协议处理器
     */
    public WSHandler getWsHandler() {
        return wsHandler;
    }

    public void setWsHandler(WSHandler wsHandler) {
        this.wsHandler = wsHandler;
    }
}
