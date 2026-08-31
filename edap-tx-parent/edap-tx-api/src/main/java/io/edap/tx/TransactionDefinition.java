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

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * 事务定义——不可变。打包传播模型、隔离级别、超时、只读、异常回滚规则。
 *
 * <p>由 {@link io.edap.tx.annotation.Transactional @Transactional} 注解解析后
 * 构造,贯穿 {@link EdapTransactionManager#getTransaction} 整个生命周期。</p>
 *
 * <p><b>默认回滚规则</b>(与 Spring 对齐):</p>
 * <ul>
 *   <li>{@link RuntimeException} / {@link Error} → 默认回滚</li>
 *   <li>checked Exception → 默认不回滚</li>
 * </ul>
 *
 * <p>业务侧可通过 {@link #rollbackFor} / {@link #noRollbackFor} 覆盖。</p>
 */
public final class TransactionDefinition {

    private final Propagation propagation;
    private final Isolation isolation;
    private final int timeout;                // 秒,-1 表示无超时
    private final boolean readOnly;
    private final String name;                // 可选事务名(日志/监控用)
    private final Set<Class<? extends Throwable>> rollbackFor;
    private final Set<Class<? extends Throwable>> noRollbackFor;

    private TransactionDefinition(Builder b) {
        this.propagation = b.propagation;
        this.isolation = b.isolation;
        this.timeout = b.timeout;
        this.readOnly = b.readOnly;
        this.name = b.name;
        this.rollbackFor = b.rollbackFor.isEmpty()
                ? Collections.emptySet()
                : Collections.unmodifiableSet(new HashSet<>(b.rollbackFor));
        this.noRollbackFor = b.noRollbackFor.isEmpty()
                ? Collections.emptySet()
                : Collections.unmodifiableSet(new HashSet<>(b.noRollbackFor));
    }

    public Propagation propagation() { return propagation; }
    public Isolation isolation()     { return isolation; }
    public int timeout()             { return timeout; }
    public boolean readOnly()        { return readOnly; }
    public String name()             { return name; }
    public Set<Class<? extends Throwable>> rollbackFor()    { return rollbackFor; }
    public Set<Class<? extends Throwable>> noRollbackFor()  { return noRollbackFor; }

    /**
     * 判断给定异常是否应触发回滚。
     *
     * <p>决策顺序:</p>
     * <ol>
     *   <li>如果命中 {@link #noRollbackFor} → 不回滚</li>
     *   <li>如果命中 {@link #rollbackFor} → 回滚</li>
     *   <li>默认规则: {@link RuntimeException} / {@link Error} → 回滚,checked → 不回滚</li>
     * </ol>
     */
    public boolean shouldRollbackOn(Throwable ex) {
        if (noRollbackFor.stream().anyMatch(c -> c.isInstance(ex))) {
            return false;
        }
        if (rollbackFor.stream().anyMatch(c -> c.isInstance(ex))) {
            return true;
        }
        // 默认规则
        return ex instanceof RuntimeException || ex instanceof Error;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof TransactionDefinition)) return false;
        TransactionDefinition that = (TransactionDefinition) o;
        return timeout == that.timeout
                && readOnly == that.readOnly
                && propagation == that.propagation
                && isolation == that.isolation
                && equalsStr(name, that.name)
                && rollbackFor.equals(that.rollbackFor)
                && noRollbackFor.equals(that.noRollbackFor);
    }

    @Override
    public int hashCode() {
        int h = propagation.hashCode();
        h = 31 * h + isolation.hashCode();
        h = 31 * h + timeout;
        h = 31 * h + (readOnly ? 1 : 0);
        h = 31 * h + (name != null ? name.hashCode() : 0);
        h = 31 * h + rollbackFor.hashCode();
        h = 31 * h + noRollbackFor.hashCode();
        return h;
    }

    private static boolean equalsStr(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

    @Override
    public String toString() {
        return "TransactionDefinition{propagation=" + propagation
                + ", isolation=" + isolation
                + ", timeout=" + timeout
                + ", readOnly=" + readOnly
                + ", name='" + name + '\''
                + '}';
    }

    /**
     * 构造器——Builder 模式,字段多时比 telescoping constructor 清晰。
     */
    public static final class Builder {
        private Propagation propagation = Propagation.REQUIRED;
        private Isolation isolation = Isolation.DEFAULT;
        private int timeout = -1;
        private boolean readOnly = false;
        private String name;
        private final Set<Class<? extends Throwable>> rollbackFor = new HashSet<>();
        private final Set<Class<? extends Throwable>> noRollbackFor = new HashSet<>();

        public Builder propagation(Propagation p) { this.propagation = p; return this; }
        public Builder isolation(Isolation i)     { this.isolation = i; return this; }
        public Builder timeout(int t)             { this.timeout = t; return this; }
        public Builder readOnly(boolean r)        { this.readOnly = r; return this; }
        public Builder name(String n)             { this.name = n; return this; }

        public Builder rollbackFor(Class<? extends Throwable>... classes) {
            Collections.addAll(this.rollbackFor, classes);
            return this;
        }

        public Builder noRollbackFor(Class<? extends Throwable>... classes) {
            Collections.addAll(this.noRollbackFor, classes);
            return this;
        }

        public TransactionDefinition build() {
            return new TransactionDefinition(this);
        }
    }

    /**
     * Builder 工厂 —— 比 {@code new TransactionDefinition.Builder()} 简洁。
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * 默认事务定义 —— 等价于空 builder,语义 REQUIRED + DEFAULT 隔离 + 无超时 +
     * 默认回滚规则。manager 在 {@code getTransaction(null)} 时使用。
     */
    public static TransactionDefinition defaultDefinition() {
        return new Builder().build();
    }
}
