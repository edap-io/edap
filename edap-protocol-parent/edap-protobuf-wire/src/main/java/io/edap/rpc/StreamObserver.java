/*
 * Copyright 2020 The edap Project
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

package io.edap.rpc;

/**
 * 流式 RPC 的观察者接口。
 *
 * <p>proto 代码生成器（{@code JavaBuilder}）对流式方法生成的签名形如
 * {@code StreamObserver<Resp> method(StreamObserver<Req>)}，其中
 * {@code StreamObserver} 即本类的完全限定名 {@code io.edap.rpc.StreamObserver}。
 *
 * <p>本类放在 {@code edap-protobuf-wire} 模块的原因：该模块是所有使用 proto 生成代码的模块的
 * 必经依赖，且当前不依赖任何运行时组件；生成代码硬编码 import 本类，
 * 放这里对纯 HTTP 应用零额外依赖。
 *
 * @param <T> 消息类型
 */
public interface StreamObserver<T> {

    /**
     * 收到一条消息。
     *
     * @param value 消息对象，不为 null
     */
    void onNext(T value);

    /**
     * 处理过程中发生异常。调用后不再有 {@link #onNext(Object)} / {@link #onCompleted()} 回调。
     *
     * @param t 异常
     */
    void onError(Throwable t);

    /**
     * 消息流正常结束。
     */
    void onCompleted();
}
