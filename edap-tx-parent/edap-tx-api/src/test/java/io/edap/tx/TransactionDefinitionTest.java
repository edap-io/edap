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

package io.edap.tx;

import io.edap.tx.isolation.Isolation;
import io.edap.tx.propagation.Propagation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TransactionDefinitionTest {

    // ============ Builder ============

    @Test
    @DisplayName("默认定义:REQUIRED + DEFAULT 隔离 + timeout=-1")
    void defaultValues() {
        TransactionDefinition d = TransactionDefinition.builder().build();
        assertEquals(Propagation.REQUIRED, d.propagation());
        assertEquals(Isolation.DEFAULT, d.isolation());
        assertEquals(-1, d.timeout());
        assertFalse(d.readOnly());
        assertEquals(null, d.name());
    }

    @Test
    @DisplayName("builder 链式调用全字段覆盖")
    void builderFullChain() {
        TransactionDefinition d = TransactionDefinition.builder()
                .propagation(Propagation.REQUIRES_NEW)
                .isolation(Isolation.SERIALIZABLE)
                .timeout(30)
                .readOnly(true)
                .name("orderService.placeOrder")
                .rollbackFor(IOException.class)
                .noRollbackFor(IllegalStateException.class)
                .build();

        assertEquals(Propagation.REQUIRES_NEW, d.propagation());
        assertEquals(Isolation.SERIALIZABLE, d.isolation());
        assertEquals(30, d.timeout());
        assertTrue(d.readOnly());
        assertEquals("orderService.placeOrder", d.name());
        assertEquals(1, d.rollbackFor().size());
        assertEquals(1, d.noRollbackFor().size());
    }

    @Test
    @DisplayName("defaultDefinition() 工厂方法等价于空 builder")
    void defaultDefinitionFactoryMatchesEmptyBuilder() {
        assertEquals(TransactionDefinition.builder().build(),
                TransactionDefinition.defaultDefinition());
    }

    // ============ shouldRollbackOn ============

    @Test
    @DisplayName("默认:RuntimeException 触发 rollback")
    void defaultRollbackOnRuntimeException() {
        TransactionDefinition d = TransactionDefinition.builder().build();
        assertTrue(d.shouldRollbackOn(new RuntimeException("oops")));
    }

    @Test
    @DisplayName("默认:Error 触发 rollback")
    void defaultRollbackOnError() {
        TransactionDefinition d = TransactionDefinition.builder().build();
        assertTrue(d.shouldRollbackOn(new OutOfMemoryError("simulated")));
    }

    @Test
    @DisplayName("默认:checked 异常不触发 rollback")
    void defaultNoRollbackOnCheckedException() {
        TransactionDefinition d = TransactionDefinition.builder().build();
        assertFalse(d.shouldRollbackOn(new IOException("checked")));
        assertFalse(d.shouldRollbackOn(new java.sql.SQLException("checked")));
    }

    @Test
    @DisplayName("rollbackFor 指定:命中时强制 rollback(即便默认规则不触发)")
    void rollbackForOverride_triggersOnChecked() {
        TransactionDefinition d = TransactionDefinition.builder()
                .rollbackFor(IOException.class)
                .build();
        assertTrue(d.shouldRollbackOn(new IOException("checked but explicit rollbackFor")));
    }

    @Test
    @DisplayName("noRollbackFor 指定:命中时强制不 rollback(即便默认规则触发)")
    void noRollbackForOverride_blocksOnRuntime() {
        TransactionDefinition d = TransactionDefinition.builder()
                .noRollbackFor(IllegalStateException.class)
                .build();
        assertFalse(d.shouldRollbackOn(new IllegalStateException("blocked")));
    }

    @Test
    @DisplayName("rollbackFor 命中子类继承:匹配父类也算命中")
    void rollbackFor_matchesInheritedType() {
        TransactionDefinition d = TransactionDefinition.builder()
                .rollbackFor(Exception.class)
                .build();
        // IOException 是 Exception 的子类,匹配父类
        assertTrue(d.shouldRollbackOn(new IOException("subclass match")));
    }

    @Test
    @DisplayName("noRollbackFor 优先级 > rollbackFor 优先级 > 默认规则")
    void priorityOrder() {
        // 同时配置:noRollbackFor + rollbackFor + 默认规则
        // → noRollbackFor 命中 → 不 rollback(noRollbackFor 优先级最高)
        TransactionDefinition d = TransactionDefinition.builder()
                .rollbackFor(IOException.class)
                .noRollbackFor(IOException.class)
                .build();
        assertFalse(d.shouldRollbackOn(new IOException("noRollbackFor wins")),
                "noRollbackFor 应优先于 rollbackFor");

        // 只 rollbackFor 命中 → rollback
        TransactionDefinition d2 = TransactionDefinition.builder()
                .rollbackFor(IOException.class)
                .noRollbackFor(IllegalStateException.class)
                .build();
        assertTrue(d2.shouldRollbackOn(new IOException("rollbackFor wins over default")));
    }

    // ============ equals / hashCode / toString ============

    @Test
    @DisplayName("equals / hashCode:全字段相同 → 相等")
    void equalsByAllFields() {
        TransactionDefinition a = TransactionDefinition.builder()
                .propagation(Propagation.NESTED)
                .isolation(Isolation.READ_COMMITTED)
                .timeout(60)
                .readOnly(true)
                .name("svc.x")
                .build();
        TransactionDefinition b = TransactionDefinition.builder()
                .propagation(Propagation.NESTED)
                .isolation(Isolation.READ_COMMITTED)
                .timeout(60)
                .readOnly(true)
                .name("svc.x")
                .build();
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    @DisplayName("equals:任一字段不同 → 不等")
    void notEqualsWhenAnyFieldDiffers() {
        TransactionDefinition base = TransactionDefinition.builder().build();
        assertNotEquals(base, TransactionDefinition.builder().propagation(Propagation.NESTED).build());
        assertNotEquals(base, TransactionDefinition.builder().isolation(Isolation.READ_COMMITTED).build());
        assertNotEquals(base, TransactionDefinition.builder().timeout(30).build());
        assertNotEquals(base, TransactionDefinition.builder().readOnly(true).build());
        assertNotEquals(base, TransactionDefinition.builder().name("x").build());
    }

    @Test
    @DisplayName("toString 含关键字段名")
    void toStringContainsKeyFields() {
        String s = TransactionDefinition.builder()
                .propagation(Propagation.REQUIRES_NEW)
                .isolation(Isolation.SERIALIZABLE)
                .name("placeOrder")
                .build().toString();
        assertTrue(s.contains("REQUIRES_NEW"));
        assertTrue(s.contains("SERIALIZABLE"));
        assertTrue(s.contains("placeOrder"));
    }
}