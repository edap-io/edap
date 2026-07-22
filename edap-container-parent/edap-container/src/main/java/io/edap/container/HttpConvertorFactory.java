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

package io.edap.container;

import io.edap.container.httpadapter.HandlerConfig;
import io.edap.container.httpadapter.HttpHandlerRegister;
import io.edap.http.HttpHandler;

import java.lang.reflect.Method;

public class HttpConvertorFactory {

    public static <T> HttpHandler createGetHandler(Method method, T bean) {
        HttpHandler handler;
        HttpHandlerRegister register = HttpHandlerRegister.instance();
        HandlerConfig handlerConfig = new HandlerConfig();
        System.out.println("method=" + method);
        handler = register.getParameterHandler(method, bean, handlerConfig);
        return handler;
    }
}
