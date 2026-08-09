package io.edap.container.mw;

/**
 * 节点部署的应用类型
 */
public enum NodeType {
    /**
     * 普通的http应用
     */
    WEB,
    /**
     * WebSocket的应用
     */
    WEB_SOCKET,
    /**
     * edap的微服务应用
     */
    ERPC,
    /**
     * 兼容gRPC的应用
     */
    GRPC
}
