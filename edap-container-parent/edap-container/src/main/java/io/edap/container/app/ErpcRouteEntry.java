package io.edap.container.app;

public final class ErpcRouteEntry {

    private final int      methodId;     // eRPC methodId（PB descriptor 算出）
    private final String   beanName;
    private final String   methodName;
    private final String   requestType;  // 请求体 FQCN，用于反序列化
    private final String   responseType; // 响应体 FQCN，用于序列化
    private final boolean  shard;        // true = 此 eRPC 路由 shard 亲和（@ShardKey 标注），handler dispatch 走 ShardRegistry

    public ErpcRouteEntry(int methodId, String beanName, String methodName,
                          String requestType, String responseType, boolean shard) {
        this.methodId = methodId;
        this.beanName = beanName;
        this.methodName = methodName;
        this.requestType = requestType;
        this.responseType = responseType;
        this.shard = shard;
    }

    public int      methodId()     { return methodId; }
    public String   beanName()     { return beanName; }
    public String   methodName()   { return methodName; }
    public String   requestType()  { return requestType; }
    public String   responseType() { return responseType; }
    public boolean  shard()        { return shard; }
}
