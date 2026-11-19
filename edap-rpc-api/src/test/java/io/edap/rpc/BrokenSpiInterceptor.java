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
 * SPI 装载失败测试用的接口（测试夹具）。
 *
 * <p>对应的 SPI 文件里写的是一个<b>不存在的类</b> ⇒ 装载必须 fail-fast。</p>
 */
public interface BrokenSpiInterceptor {

    /**
     * @return 拦截器名字
     */
    String name();
}
