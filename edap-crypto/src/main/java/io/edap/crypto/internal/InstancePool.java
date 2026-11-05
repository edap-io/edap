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

package io.edap.crypto.internal;

import java.util.function.Supplier;

/**
 * 每线程一个 JCE 实例的「独占池」。
 *
 * <p><b>为什么需要独占语义</b>：JCE 的 {@code MessageDigest} / {@code Mac} /
 * {@code Cipher} 都<b>非</b>线程安全，而且它们的 {@code reset()} / {@code init()}
 * 会清掉内部状态。如果同一线程里两个<b>活</b> session 共用同一个实例，
 * 后一个的 {@code init} 会把前一个已喂进去的数据清掉 —— 前一个算出来的摘要/密文
 * 就是错的，而且<b>不会报错</b>。所以实例必须被 session 独占。</p>
 *
 * <p><b>槽位模型</b>：每线程一个槽位，存着一个「空闲实例」。</p>
 * <ul>
 *   <li>{@link #acquire()}：槽位有货就取走（独占），没货就新建一个；</li>
 *   <li>{@link #release(Object)}：把实例还回槽位（槽位已被别人占着就丢弃）。</li>
 * </ul>
 *
 * <p>于是「同线程两个活 session」的场景下，第二个拿不到第一个的实例 ⇒ 各自独立，
 * 互不干扰；而常见的「一次只用一个 session」场景下，实例在
 * {@code digest()}/{@code doFinal()} 后立刻回到槽位，下一次直接复用 ⇒
 * 省掉 {@code getInstance} 的 1~3μs。</p>
 *
 * <p><b>线程安全</b>：槽位是 {@link ThreadLocal}，实例不会被跨线程共享。</p>
 *
 * @param <T> 实例类型
 */
public final class InstancePool<T> {

    private final Supplier<T> factory;

    /**
     * 槽位：空闲实例。{@code null} 表示当前没有空闲实例（或已被别的 session 独占）。
     */
    private final ThreadLocal<T> slot;

    public InstancePool(Supplier<T> factory) {
        if (factory == null) {
            throw new IllegalArgumentException("instance factory required");
        }
        this.factory = factory;
        this.slot = ThreadLocal.withInitial(factory);
    }

    /**
     * 独占一个实例。
     *
     * <p>槽位里没有空闲实例时<b>新建</b>一个（不会去抢别的 session 正在用的实例）。</p>
     */
    public T acquire() {
        T instance = slot.get();
        if (instance == null) {
            return factory.get();
        }
        // 把槽位腾空 —— 这个实例归调用方独占
        slot.set(null);
        return instance;
    }

    /**
     * 归还实例。
     *
     * <p>槽位已被别的实例占用时直接丢弃 —— 正确性优先，池满就丢弃。</p>
     */
    public void release(T instance) {
        if (instance == null) {
            return;
        }
        if (slot.get() == null) {
            slot.set(instance);
        }
    }
}
