package io.edap.container;

import java.lang.reflect.Method;

/**
 * 多节点分片路由器。封装 ShardRegistry（本地分片）+ RpcClient（远端 RPC）+ 集群拓扑，
 * 给 Handler 生成器（§3.5.7）一个统一的"分片路由"入口。
 *
 * 路由决策模型：
 *   假设集群有 N 个节点，每个节点的每个 stateful bean 有 localShardCount 个分片实例，
 *   则全集群总共 N × localShardCount 个分片。给定 shardKey：
 *
 *     globalIdx   = Math.abs(shardKey.hashCode()) % (N × localShardCount)
 *     owningNode  = globalIdx / localShardCount   // 0..N-1
 *     localIdx    = globalIdx % localShardCount   // 0..localShardCount-1
 *
 *   如果 owningNode == currentNodeIdx → 本节点直接 invokevirtual shard[localIdx].method(...)
 *   否则 → 通过 RpcClient 跨节点调用，RPC server 端按同样的 hash 找到目标分片实例执行
 *
 * 与 ShardRegistry 的关系：
 *   ShardRegistry 仅承担"本节点分片存储 + 查找"，不知道集群拓扑
 *   ClusterShardRouter 是集群感知的层，包 ShardRegistry 提供本地查找
 *
 * 协作契约（Handler 生成器 §3.5.7）：
 *   生成 Handler.handle(req, resp) 字节码中（entry.shard() == true 时）：
 *     1. 从 req 提参 → 提取 shardKey
 *     2. router.targetNode(shardKey) → 拿到 owningNode
 *     3. if (owningNode == router.currentNodeIdx()) {
 *            Object inst = router.localInstance(beanName, shardKey);
 *            Object result = inst.method(args);   // invokevirtual
 *        } else {
 *            Object result = router.invokeRemote(beanName, shardKey, method, args);
 *        }
 *     4. 写 resp
 */
public class ClusterShardRouter {

    private final ShardRegistry local;
    private final RpcClient     rpc;
    private final int           localShardCount;
    private final int           clusterSize;
    private final int           currentNodeIdx;

    public ClusterShardRouter(ShardRegistry local, RpcClient rpc,
                              int localShardCount, int clusterSize, int currentNodeIdx) {
        if (localShardCount < 1) throw new IllegalArgumentException("localShardCount must be >= 1");
        if (clusterSize < 1)     throw new IllegalArgumentException("clusterSize must be >= 1");
        if (currentNodeIdx < 0 || currentNodeIdx >= clusterSize) {
            throw new IllegalArgumentException("currentNodeIdx out of range");
        }
        this.local           = local;
        this.rpc             = rpc;
        this.localShardCount = localShardCount;
        this.clusterSize     = clusterSize;
        this.currentNodeIdx  = currentNodeIdx;
    }

    public int localShardCount() { return localShardCount; }
    public int clusterSize()     { return clusterSize; }
    public int currentNodeIdx()  { return currentNodeIdx; }
    public ShardRegistry localShardRegistry() { return local; }

    /** 计算 shardKey 应路由到哪个节点。 */
    public int targetNode(String shardKey) {
        int totalShards = localShardCount * clusterSize;
        int globalIdx   = Math.abs(shardKey.hashCode()) % totalShards;
        return globalIdx / localShardCount;
    }

    /** 当前节点是否拥有该 shardKey 对应的分片。 */
    public boolean isLocal(String shardKey) {
        return targetNode(shardKey) == currentNodeIdx;
    }

    /**
     * 取本节点分片实例。前提：isLocal(shardKey) == true；否则抛 IllegalStateException
     * （调用方应先 isLocal 判断，再决定走 localInstance 还是 invokeRemote）。
     */
    public Object localInstance(String beanName, String shardKey) {
        if (!isLocal(shardKey)) {
            throw new IllegalStateException(
                "Shard not local: beanName=" + beanName + ", shardKey=" + shardKey
                + ", owningNode=" + targetNode(shardKey) + ", currentNode=" + currentNodeIdx);
        }
        int totalShards = localShardCount * clusterSize;
        int globalIdx   = Math.abs(shardKey.hashCode()) % totalShards;
        int localIdx    = globalIdx % localShardCount;
        return local.routeByIndex(beanName, localIdx);
    }

    /**
     * 跨节点 RPC 调用目标分片。前提：isLocal(shardKey) == false；否则抛 IllegalStateException
     * （避免"是本节点却走了 RPC"的浪费）。
     */
    public Object invokeRemote(String beanName, String shardKey, Method method, Object[] args) {
        if (isLocal(shardKey)) {
            throw new IllegalStateException(
                "Shard is local, use localInstance: beanName=" + beanName + ", shardKey=" + shardKey);
        }
        int target = targetNode(shardKey);
        return rpc.invoke(beanName, target, shardKey, method, args);
    }
}