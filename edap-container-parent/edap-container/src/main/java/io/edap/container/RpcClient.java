package io.edap.container;

import java.lang.reflect.Method;

/**
 * 跨节点 RPC 客户端抽象。ClusterShardRouter 在目标分片不在本节点时调用本接口，
 * 由具体实现（eRPC / gRPC）把方法调用序列化发到目标节点。
 *
 * 协议无关：实现可以是 eRPC（protobuf 二进制） / gRPC（HTTP/2 + protobuf） / 自定义协议。
 * ClusterShardRouter 不感知协议细节，只调 {@link #invoke}。
 */
public interface RpcClient {

    /**
     * 把 bean 方法调用发到目标节点的指定分片实例上执行，返回结果。
     *
     * @param beanName    bean 名（目标节点用此找到对应实例）
     * @param targetNode  目标节点 idx（0..clusterSize-1）
     * @param shardKey    分片 key（目标节点用此选分片实例，可与本节点相同 hash 空间）
     * @param method      bean 方法反射对象
     * @param args        方法入参（已按 method 序列化）
     * @return 方法返回值（已按 method 反序列化）
     */
    Object invoke(String beanName, int targetNode, String shardKey, Method method, Object[] args);
}