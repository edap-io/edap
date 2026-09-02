package io.edap.tx;

import io.edap.tx.isolation.Isolation;
import io.edap.tx.propagation.Propagation;

import java.util.HashMap;
import java.util.Map;

public class TransactionDefinitionFactory {

    static final Map<String, TransactionDefinition> CACHE = new HashMap<>();

    /**
     * 按 7 元组参数(传播模型 / 隔离级别 / 超时 / 只读 / 事务名 / 回滚异常集 / 失败兜底异常集)
     * 缓存 {@link TransactionDefinition} —— 同 key 直接复用,避免每次调用都走 builder。
     *
     * <p>接受 {@code Class[]} 而非 {@code Set} 是为了让调用方在 ASM 字节码里
     * 直接 emit 字面量数组(anewarray + aastore),省去 Set 构造的额外指令;
     * null / 空数组在缓存 key 里等价,本方法统一视为 {@code Class[0]} 传给 builder。</p>
     *
     * <p>线程安全:HashMap 的 put/get 在 JVM 里对单 key 是顺序一致的,最坏情况下
     * 多个线程同时 miss 会各自 build 一次后写同一 key —— 浪费一次构造,不影响正确性。</p>
     */
    public static TransactionDefinition getTransactionDefinition(Propagation propagation,
                                                          Isolation isolation,
                                                          int timeout,
                                                          boolean readOnly,
                                                          String name,
                                                          Class<? extends Throwable>[] rollbackFor,
                                                          Class<? extends Throwable>[] noRollbackFor) {
        StringBuilder keyStr = new StringBuilder();
        keyStr.append(propagation==null?Propagation.REQUIRED.name():propagation.name()).append('_');
        keyStr.append(isolation==null?Isolation.DEFAULT.name():isolation.name()).append('_');
        keyStr.append(timeout).append('_');
        keyStr.append(readOnly).append('_');
        keyStr.append(name==null?"":name).append('_');
        appendClassArrayKey(keyStr, rollbackFor);
        keyStr.append('_');
        appendClassArrayKey(keyStr, noRollbackFor);

        TransactionDefinition transactionDefinition = CACHE.get(keyStr.toString());
        if (transactionDefinition == null) {
            TransactionDefinition.Builder builder = TransactionDefinition.builder();
            transactionDefinition = builder.propagation(propagation)
                    .isolation(isolation)
                    .timeout(timeout)
                    .readOnly(readOnly)
                    .name(name)
                    .rollbackFor(rollbackFor == null ? new Class[0] : rollbackFor)
                    .noRollbackFor(noRollbackFor == null ? new Class[0] : noRollbackFor)
                    .build();
            CACHE.put(keyStr.toString(), transactionDefinition);
        }

        return transactionDefinition;
    }

    /** 把 Class[] 序列化成 [a,b,c] 形式追加到 key,空 / null → []。 */
    private static void appendClassArrayKey(StringBuilder keyStr, Class<? extends Throwable>[] arr) {
        keyStr.append('[');
        if (arr != null) {
            boolean hasLen = false;
            for (Class<?> c : arr) {
                if (c != null) {
                    if (hasLen) {
                        keyStr.append(',');
                    }
                    keyStr.append(c.getName());
                    hasLen = true;
                }
            }
        }
        keyStr.append(']');
    }
}
