/*
 * Copyright (c) 2019 louis.lu
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */

package io.edap;

/**
 * Edap 进程停止时的回调点。
 *
 * 由外部组件（如 Container）实现，注册到 {@link Edap#addOnStop(Stoppable)}。
 * Edap.stop() / doStop() 在关闭 ServerGroup 之前按注册顺序调用所有 hook。
 *
 * 实现方应自行捕获内部异常；Edap 对每个 hook 独立 try/catch，单个 hook 失败不影响其他 hook。
 */
public interface Stoppable {
    void stop() throws Exception;
}