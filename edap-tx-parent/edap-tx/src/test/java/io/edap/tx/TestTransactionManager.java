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

import io.edap.tx.exception.TransactionException;

import java.util.ArrayList;
import java.util.List;

/**
 * Phase 1 测试用 manager —— 把 {@link DefaultEdapTransactionManager#doBegin}
 * 的默认抛错实现替换为返回 {@link MockTransactionResource}。
 *
 * <p>同时记录每次 doBegin 调用的 definition,便于断言。</p>
 */
public class TestTransactionManager extends DefaultEdapTransactionManager {

    public final List<TransactionDefinition> beginDefinitions = new ArrayList<>();

    @Override
    protected TransactionResource doBegin(TransactionDefinition definition, Object resourceKey)
            throws TransactionException {
        beginDefinitions.add(definition);
        return new MockTransactionResource();
    }
}