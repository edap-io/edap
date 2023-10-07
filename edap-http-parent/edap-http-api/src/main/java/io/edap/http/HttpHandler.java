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

import java.io.Serializable;

/**
 * HTTP处理器的接口定义
 */
@FunctionalInterface
public interface HttpHandler extends Serializable {
    /**
     * HTTP请求的处理接口，处理HTTP请求并返回响应的数据
     * @param req HTTP请求
     * @param resp HTTP的相应实例
     */
    void handle(HttpRequest req, HttpResponse resp);
}
