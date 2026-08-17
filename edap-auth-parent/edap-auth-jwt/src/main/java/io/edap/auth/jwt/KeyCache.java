package io.edap.auth.jwt;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * 线程安全的 LRU 缓存：按 {@code "alg:key"} 缓存 {@link Algorithm} 实例。
 *
 * <p>设计目标：
 * <ul>
 *   <li><b>线程安全</b>：{@code synchronized} 串行化所有访问；热点为 cache hit，开销可忽略</li>
 *   <li><b>LRU 淘汰</b>：基于 {@link LinkedHashMap} 的 access-order；超容量自动淘汰最久未用 key</li>
 *   <li><b>无界防护</b>：默认 64 个 key 上限，防止恶意/异常场景下 key 多样化耗尽堆</li>
 * </ul>
 *
 * <p>替代旧版 {@code HashMap} 静态字段（{@code ALGORITHM_CACHE}）——
 * 旧版在并发 {@code put} 下可能死循环（Java 7 经典 bug）/NPE/丢失更新。</p>
 */
public final class KeyCache {

    private static final int DEFAULT_MAX_KEYS = 64;

    private final LinkedHashMap<String, Algorithm> cache;

    public KeyCache() {
        this(DEFAULT_MAX_KEYS);
    }

    public KeyCache(int maxSize) {
        this.cache = new LinkedHashMap<String, Algorithm>(maxSize + 1, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Algorithm> eldest) {
                return size() > maxSize;
            }
        };
    }

    /**
     * 获取或创建 {@link Algorithm} 实例（线程安全，原子）。
     *
     * <p>cache hit 直接返回（access-order 顺带更新 LRU 位置）；cache miss 经工厂
     * 创建后写入缓存，超容量自动 LRU 淘汰。</p>
     *
     * @param alg     算法名（HS256 / RS256 等）
     * @param key     密钥（HS256 为 signKey；RS256 为 PEM 私钥等）
     * @param factory 创建工厂：接收 {@code key}，返回 Algorithm 实例
     * @return 已缓存或新创建的 Algorithm 实例
     */
    public synchronized Algorithm getOrCreate(String alg, String key, Function<String, Algorithm> factory) {
        String compositeKey = alg + ":" + key;
        Algorithm existing = cache.get(compositeKey);
        if (existing != null) {
            return existing;
        }
        Algorithm created = factory.apply(key);
        // LinkedHashMap.put 内部触发 removeEldestEntry，超 maxSize 自动淘汰最久未用
        cache.put(compositeKey, created);
        return created;
    }

    /** 当前缓存条目数（仅用于监控/测试） */
    public synchronized int size() {
        return cache.size();
    }

    /** 清空缓存（仅用于测试或紧急恢复） */
    public synchronized void clear() {
        cache.clear();
    }
}