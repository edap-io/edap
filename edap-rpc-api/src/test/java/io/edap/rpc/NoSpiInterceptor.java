/*
 * Copyright 2023 The edap Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at:
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.edap.rpc;

/**
 * 没有对应 SPI 文件的拦截器接口（测试夹具）——
 * 用来断言「没有 SPI 文件 ⇒ 装出空列表」。
 */
public interface NoSpiInterceptor {

    /**
     * @return 拦截器名字
     */
    String name();
}
