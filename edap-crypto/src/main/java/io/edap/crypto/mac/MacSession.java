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

package io.edap.crypto.mac;

/**
 * MAC 会话 —— 真正与线程绑定的单位。
 *
 * <p><b>线程绑定</b>：一个 session 不可跨线程使用。</p>
 */
public interface MacSession extends AutoCloseable {

    /**
     * 追加一段数据。
     */
    void update(byte[] data, int offset, int len);

    /**
     * 追加一段数据。
     */
    default void update(byte[] data) {
        update(data, 0, data.length);
    }

    /**
     * 计算 MAC 值。
     *
     * <p><b>调用后 session 回到初始状态</b>（已喂的数据被丢弃，key 保留，
     * 可以继续喂新数据再算一次）。所以不喂数据连续调两次，第二次得到的是
     * 「空数据」的 MAC —— 这与 JCE {@code Mac.doFinal()} 的语义一致。</p>
     */
    byte[] doFinal();

    /**
     * 重置到初始状态（保留 key）。
     */
    void reset();

    @Override
    default void close() {
        // 默认无资源需要释放
    }
}
