package io.edap.container;

import io.edap.container.exc.NoSuchBeanException;
import io.edap.container.exc.ShardCloneFailedException;

import java.util.HashMap;
import java.util.Map;

/**
 * 分片实例注册表。@Sharded 标注方法的 bean 模板实例会被扩展为 N 个分片实例，
 * 运行时按 shardKey 哈希到本节点内的分片 idx。
 *
 * <p>分片数不在 BeanDef 里固化——开发者部署时无法预知数据量与机器配置，
 * 由 {@code ClusterShardRouter} 在运行时根据集群拓扑/资源状况计算，传给 {@link #registerSharded}。
 * 扩容/缩容时只需重新调一次 registerSharded 覆盖即可。</p>
 *
 * **简化模型**：sharding 的主用例是"本地资源不够 → 多节点分担负载"——
 * shardCount 表示**本节点当前持有的分片数**（运行时由 ClusterShardRouter 决定）。
 * 不引入"intra-node 并行分片"的优化路径——本地 shard 意义不大，没必要做。
 *
 * 路由语义：
 *   - route(beanName, shardKey)：本节点内分片查找，按 hash(shardKey) % localShardCount 选 idx
 *   - routeByIndex(beanName, localIdx)：直接按 idx 查（ClusterShardRouter 计算好 idx 后调用，避开了按 localShardCount hash 导致错位的问题）
 *
 * 与 ClusterShardRouter 的关系：
 *   ShardRegistry 仅承担"本节点分片存储 + 查找"，不知道集群拓扑
 *   ClusterShardRouter 是集群感知的层，包 ShardRegistry 提供本地查找，
 *   并在路由前/扩容缩容时决定 shardCount
 *
 * 设计要点：
 *   - 模板克隆：默认走 prototype 路径（无参构造器）；应用可定制 clonePrototype hook
 *   - 生命周期：registerSharded 由 ClusterShardRouter 在运行时调（启动初始化 + 拓扑变化时重建）；
 *     clear() 由 BeanContainer.destroyAllSingletons 在 AppContext.stop 期间调用，释放分片实例链
 *   - 并发：注册期单线程（持有 lifecycleLock）；运行时 route() 多线程读 Map<Integer,Object>，
 *     由于 registerSharded 一次性 put 后不再修改，HashMap 无并发写风险，无需 CHM
 */
public class ShardRegistry {

    /** beanName → { shardIdx : instance } */
    private final Map<String, Map<Integer, Object>> shards = new HashMap<>();

    /**
     * 把 @Sharded 方法所属 bean 的 template 实例扩展为 shardCount 个分片实例。
     * shardCount 由 ClusterShardRouter 在运行时计算后传入。
     */
    public void registerSharded(String beanName, Object template, int shardCount) {
        int n = Math.max(1, shardCount);
        Map<Integer, Object> map = new HashMap<>(n);
        for (int i = 0; i < n; i++) {
            map.put(i, clonePrototype(template, i));   // 每个分片独立实例
        }
        shards.put(beanName, map);
    }

    /**
     * 按 shardKey hash 到本节点内分片 idx 查实例。
     *
     * 注意：本方法只用于"shardCount 就是本节点分片数"的场景（即单节点部署，shardCount 全部在本节点）。
     * 多节点集群下应使用 {@link #routeByIndex(String, int)}，由 ClusterShardRouter 计算好 localIdx 后
     * 调用——避免本方法 hash 出错的 idx（hash 按 localShardCount 而非 totalShardCount）。
     *
     * @throws NoSuchBeanException beanName 未注册
     */
    public Object route(String beanName, String shardKey) {
        Map<Integer, Object> map = shards.get(beanName);
        if (map == null) throw new NoSuchBeanException(beanName);
        int shardCount = map.size();
        int idx = Math.abs(shardKey.hashCode()) % shardCount;
        return map.get(idx);
    }

    /**
     * 按本地分片 idx 直接查实例（不做 hash 计算）。
     *
     * 用法：ClusterShardRouter 计算出 globalIdx 后映射到 localIdx（globalIdx % localShardCount），
     * 再调本方法取实例。多节点集群下使用，避免 route() 按 localShardCount hash 导致的分片错位。
     *
     * @param beanName bean 名
     * @param localIdx 本节点内分片 idx（0..localShardCount-1）
     * @throws NoSuchBeanException beanName 未注册
     */
    public Object routeByIndex(String beanName, int localIdx) {
        Map<Integer, Object> map = shards.get(beanName);
        if (map == null) throw new NoSuchBeanException(beanName);
        return map.get(localIdx);
    }

    /** 销毁（AppContext.stop 期间）：清空所有分片引用。 */
    public void clear() {
        shards.clear();
    }

    /** 所有分片实例数（用于自检 beans.statefulTotal）。 */
    public int size() {
        return shards.values().stream().mapToInt(Map::size).sum();
    }

    /** 当前已注册分片的 beanName 数（用于自检）。 */
    public int beanCount() {
        return shards.size();
    }

    /**
     * 模板实例克隆 hook——默认浅克隆（无参构造器），应用可按需覆写。
     * 注意：实例应可独立持有状态；@Sharded bean 的字段都是 per-shard 状态。
     */
    private Object clonePrototype(Object template, int idx) {
        try {
            // 默认走 prototype scope 的 instantiate 路径走一遍；具体策略由 BeanContainer 注入
            return template.getClass().getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException e) {
            throw new ShardCloneFailedException(template.getClass(), idx, e);
        }
    }
}