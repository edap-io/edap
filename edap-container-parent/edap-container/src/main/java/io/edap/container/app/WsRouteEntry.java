package io.edap.container.app;

public final class WsRouteEntry {

    private final String  path;
    private final String  beanName;
    private final String  methodName;     // 入口方法名，bean 内部按 message.method 字段二次分发
    private final String  msgType;        // "java.lang.String" 或 "byte[]"，决定生成类 WSServiceMsgHandler<T> 中 T 的具体类型
    private final boolean shard;          // true = 此 WS 路由 shard 亲和（@ShardKey 标注），handler dispatch 走 ShardRegistry

    public WsRouteEntry(String path, String beanName, String methodName, String msgType, boolean shard) {
        this.path = path;
        this.beanName = beanName;
        this.methodName = methodName;
        this.msgType = msgType;
        this.shard = shard;
    }

    public String  path()       { return path; }
    public String  beanName()   { return beanName; }
    public String  methodName() { return methodName; }
    public String  msgType()    { return msgType; }
    public boolean shard()      { return shard; }
}
