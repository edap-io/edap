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

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link InterceptorChain} 的顺序 / 失败语义测试。
 */
public class InterceptorChainTest {

    /** 记录名字的拦截器。 */
    static class Named {

        final String name;
        final List<String> log;

        Named(String name, List<String> log) {
            this.name = name;
            this.log = log;
        }

        @Override
        public String toString() {
            return name;
        }
    }

    @Test
    public void beforeRunsInRegistrationOrder() throws Exception {
        List<String> log = new ArrayList<>();
        List<Named> interceptors = Arrays.asList(
            new Named("a", log), new Named("b", log), new Named("c", log));

        InterceptorChain.before(interceptors, i -> log.add(i.name));

        assertEquals(Arrays.asList("a", "b", "c"), log);
    }

    @Test
    public void beforeStopsAtFirstFailureAndRethrowsAsIs() {
        List<String> log = new ArrayList<>();
        RuntimeException boom = new RuntimeException("boom");
        List<Named> interceptors = Arrays.asList(
            new Named("a", log), new Named("b", log), new Named("c", log));

        RuntimeException thrown = assertThrows(RuntimeException.class,
            () -> InterceptorChain.before(interceptors, i -> {
                if ("b".equals(i.name)) {
                    throw boom;
                }
                log.add(i.name);
            }));

        // 原样抛出（不是包装过的）
        assertSame(boom, thrown);
        // 「b」之后的拦截器不再跑
        assertEquals(Collections.singletonList("a"), log);
    }

    @Test
    public void afterRunsInReverseOrder() {
        List<String> log = new ArrayList<>();
        List<Named> interceptors = Arrays.asList(
            new Named("a", log), new Named("b", log), new Named("c", log));

        InterceptorChain.after(interceptors, i -> log.add(i.name));

        assertEquals(Arrays.asList("c", "b", "a"), log);
    }

    @Test
    public void afterSwallowsFailureAndKeepsGoing() {
        List<String> log = new ArrayList<>();
        List<Named> interceptors = Arrays.asList(
            new Named("a", log), new Named("b", log), new Named("c", log));

        // 不抛异常 —— 单个拦截器抛错不影响后面的
        InterceptorChain.after(interceptors, i -> {
            log.add(i.name);
            if ("b".equals(i.name)) {
                throw new RuntimeException("boom");
            }
        });

        assertEquals(Arrays.asList("c", "b", "a"), log);
    }

    @Test
    public void nullAndEmptyListsAreNoOps() throws Exception {
        List<String> log = new ArrayList<>();

        InterceptorChain.before(null, i -> log.add("x"));
        InterceptorChain.before(Collections.emptyList(), i -> log.add("x"));
        InterceptorChain.after(null, i -> log.add("x"));
        InterceptorChain.after(Collections.emptyList(), i -> log.add("x"));

        assertTrue(log.isEmpty());
    }
}
