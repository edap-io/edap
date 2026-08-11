package io.edap.container.consts;

public class CoreConstant {

    private CoreConstant() {}

    public static final int SUCCESS = 0;

    /**
     * WebSocket的默认路径
     */
    public static final String WEBSOCKET_DEFAULT_PATH = "/ws";
    /**
     * 默认的http的接口，同时支持http、websocket以及gRPC，通过http协议版本以及路径区分是gRPC还是普通的http请求
     */
    public static final int HTTP_PORT = 8080;
    /**
     * edap自定义微服务的默认端口号
     */
    public static final int ERPC_PORT = 8081;

    public static final String APP_SERVER_GROUO_KEY = "app_server";
    /**
     * 从环境变量获取节点应用类型时的key
     */
    public static final String NODE_TYPE_KEY = "EDAP_NODE_TYPE";
}
