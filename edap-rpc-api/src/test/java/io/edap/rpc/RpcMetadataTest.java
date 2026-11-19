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
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.edap.rpc;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link RpcMetadata} 的单元测试 —— 元数据是<b>会上线</b>的那一份键值对。
 */
public class RpcMetadataTest {

    @Test
    public void putGetRemove() {
        RpcMetadata m = RpcMetadata.create();
        assertTrue(m.isEmpty());
        assertEquals(0, m.size());

        m.put("txid", "0f9a-42");
        assertEquals("0f9a-42", m.get("txid"));
        assertTrue(m.containsKey("txid"));
        assertEquals(1, m.size());
        assertFalse(m.isEmpty());

        assertEquals("0f9a-42", m.remove("txid"));
        assertNull(m.get("txid"));
        assertTrue(m.isEmpty());
    }

    /**
     * ★ 值传 {@code null} ⇒ 相当于移除（不是「存一个 null 值」）。
     */
    @Test
    public void nullValueRemovesTheEntry() {
        RpcMetadata m = RpcMetadata.create();
        m.put("txid", "abc");
        m.put("txid", null);
        assertFalse(m.containsKey("txid"));
        assertTrue(m.isEmpty());
    }

    /**
     * ★ 键非法 ⇒ <b>fail-fast 抛异常</b>（不静默丢 —— 静默丢会让 txId 悄悄消失）。
     */
    @Test
    public void illegalKeysAreRejected() {
        RpcMetadata m = RpcMetadata.create();
        assertThrows(IllegalArgumentException.class, () -> m.put(null, "v"));
        assertThrows(IllegalArgumentException.class, () -> m.put("", "v"));
        // 必须以小写 ASCII 字母开头
        assertThrows(IllegalArgumentException.class, () -> m.put("Txid", "v"));
        assertThrows(IllegalArgumentException.class, () -> m.put("1txid", "v"));
        // 其余字符只允许 [a-z0-9-_.]
        assertThrows(IllegalArgumentException.class, () -> m.put("tx/id", "v"));
        assertThrows(IllegalArgumentException.class, () -> m.put("tx id", "v"));
        assertThrows(IllegalArgumentException.class, () -> m.put("tx:id", "v"));
    }

    @Test
    public void legalKeysAreAccepted() {
        RpcMetadata m = RpcMetadata.create();
        m.put("txid", "v");
        m.put("tx-id", "v");
        m.put("tx_id", "v");
        m.put("tx.id", "v");
        m.put("tx0", "v");
        m.put("a", "v");
        assertEquals(6, m.size());
    }

    /**
     * ★ 共享的只读空实例：写它 ⇒ 抛 {@link IllegalStateException}。
     */
    @Test
    public void sharedEmptyInstanceIsReadOnly() {
        RpcMetadata empty = RpcMetadata.empty();
        assertTrue(empty.isEmpty());
        assertThrows(IllegalStateException.class, () -> empty.put("txid", "v"));
        // ★ 它是全局共享的同一个实例 ⇒ 一个线程误写会污染所有线程
        assertTrue(RpcMetadata.empty() == empty);
    }

    /**
     * ★ {@code asMap()} 是<b>快照</b>（不可变），不是活视图。
     */
    @Test
    public void asMapIsAnImmutableSnapshot() {
        RpcMetadata m = RpcMetadata.create();
        m.put("txid", "v1");
        Map<String, String> snap = m.asMap();
        assertEquals(1, snap.size());
        assertEquals("v1", snap.get("txid"));
        // 改了原表 ⇒ 快照不变
        m.put("txid", "v2");
        assertEquals("v1", snap.get("txid"));
        assertThrows(UnsupportedOperationException.class, () -> snap.put("k", "v"));
    }

    /**
     * ★ {@code keys()} 是活视图（会跟着变）—— 要遍历全部条目请用 {@code asMap()}。
     */
    @Test
    public void keysIsALiveView() {
        RpcMetadata m = RpcMetadata.create();
        assertTrue(m.keys().isEmpty());
        m.put("txid", "v");
        assertEquals(1, m.keys().size());
    }

    // ------------------------------------------------------------------
    // RpcContext 的集成
    // ------------------------------------------------------------------

    /**
     * ★ {@code RpcContext.metadata()} 永远不返回 null。
     */
    @Test
    public void contextMetadataIsNeverNull() {
        RpcContext ctx = RpcContext.builder().build();
        assertNotNullMetadata(ctx);
        assertTrue(ctx.metadata().isEmpty());
        assertFalse(ctx.hasMetadata(), "没写过元数据 ⇒ hasMetadata 为 false");
    }

    /**
     * ★ ROOT（没绑上下文）上的 {@code metadata()} 是只读的共享空实例。
     */
    @Test
    public void rootContextMetadataIsReadOnly() {
        assertThrows(IllegalStateException.class,
            () -> RpcContext.current().metadata().put("txid", "v"));
    }

    /**
     * ★ {@code hasMetadata()} <b>不触发懒创建</b> —— 这是它存在的理由
     * （传输层用它决定「这一帧要不要带上下文段」，不能每次调用都白分配一个 map）。
     */
    @Test
    public void hasMetadataDoesNotCreateTheContainer() {
        RpcContext ctx = RpcContext.builder().build();
        assertFalse(ctx.hasMetadata());
        assertFalse(ctx.hasMetadata());
        ctx.metadata().put("txid", "v");
        assertTrue(ctx.hasMetadata());
    }

    /**
     * ★ {@code Builder.metadata()} 预置的容器就是上下文里那一个。
     */
    @Test
    public void builderInjectsTheContainer() {
        RpcMetadata m = RpcMetadata.create();
        m.put("txid", "v");
        RpcContext ctx = RpcContext.builder().metadata(m).build();
        assertTrue(ctx.metadata() == m, "预置的容器应当原样使用");
        assertTrue(ctx.hasMetadata());
    }

    private static void assertNotNullMetadata(RpcContext ctx) {
        assertTrue(ctx.metadata() != null, "metadata() 永远不返回 null");
    }
}
