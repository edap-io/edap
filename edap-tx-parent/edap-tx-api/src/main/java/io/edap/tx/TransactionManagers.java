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

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * {@link EdapTransactionManager} 的 JVM 全局静态注册表 —— 取代原来的
 * {@code TransactionManagerResolver} 注入式设计。
 *
 * <p><b>为什么用静态注册表而不是 DI 注入</b>:</p>
 * <ul>
 *   <li>每个 wrapper 类在 {@code <clinit>} 期调一次 {@link #get(String)} 把结果缓存到
 *       {@code static final TM_<i>} 字段 —— 运行期 wrapper 方法体只 GETSTATIC,
 *       零查找、零分配、零 indirection。</li>
 *   <li>TM 数量极少(一般 1 个默认 + 几个具名),且都是单例 —— 静态注册表完全够用,
 *       不需要为每个 bean 实例独立持有 resolver。</li>
 *   <li>把"容器配置"压平成"全局状态":容器启动期一次性 register,
 *       之后任何业务模块/wrapper 类都能直接拿到,不需要通过 bean 容器层层取。</li>
 * </ul>
 *
 * <p><b>注册时机</b>:必须在 wrapper 类加载 <b>之前</b> 完成。
 * 容器在 {@code AppContext.start()} 扫描 DataSource bean 时注册,此时早于
 * {@link io.edap.container.transactional.TransactionalBeanPostProcessor}
 * 织入 wrapper —— 时序天然保证。</p>
 *
 * <p><b>线程安全</b>:基于 {@link ConcurrentHashMap},允许 register 与 get 并发;
 * 同名重复 register 覆盖(框架内部假定每个 DataSource bean 名唯一)。</p>
 */
public final class TransactionManagers {

    private static final ConcurrentMap<String, EdapTransactionManager> REGISTRY = new ConcurrentHashMap<>();

    private TransactionManagers() {}

    /**
     * 注册一个具名 TM。同名再次 register 覆盖,常用于热替换。
     *
     * @param name bean 名(方法注解 {@code transactionManager = "..."} 的查表 key);
     *             空串 {@code ""} 表示默认 TM(单 DataSource 时自动注册)
     * @param tm   不能 null
     */
    public static void register(String name, EdapTransactionManager tm) {
        if (tm == null) {
            throw new IllegalArgumentException("tm == null");
        }
        REGISTRY.put(name == null ? "" : name, tm);
    }

    /**
     * 按名取 TM。未注册则抛 {@link IllegalStateException}(fail-fast —— 业务方以为路由
     * 到了目标 tm 实际开在错误 ds 上,这种 bug 极难定位,所以宁可启动失败也不要 fallback)。
     *
     * @param name 注解里的 {@code transactionManager()} 值;空串走默认 TM
     */
    public static EdapTransactionManager get(String name) {
        String key = name == null ? "" : name;
        EdapTransactionManager tm = REGISTRY.get(key);
        if (tm == null) {
            throw new IllegalStateException(
                    "No EdapTransactionManager registered with name '" + key
                    + "' (registered: " + REGISTRY.keySet() + ")");
        }
        return tm;
    }

    /**
     * 测试用 —— 清空注册表。生产代码不应该调这个。
     */
    static void clearForTests() {
        REGISTRY.clear();
    }
}