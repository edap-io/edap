package io.edap.container.app;

public final class HttpRouteEntry {
    private final String   method;     // "GET" / "POST" / "PUT" / "DELETE" / "PATCH"，与 @HttpRoute.method() 字面值一致
    private final String   path;       // "/v1/hello"
    private final String   beanName;   // "helloServiceImpl"
    private final String   methodName; // bean 方法名（"sayHello"），不持有 Method 对象
    private final boolean  hasBody;    // path 上的 body="*" 标记
    private final String[] pathParams; // 解析出的 {id} / {name} 顺序（用于 handler 拼装）
    private final boolean  shard;      // true = 此路由 shard 亲和（@ShardKey 标注），handler dispatch 走 ShardRegistry

    public HttpRouteEntry(String method, String path, String beanName,
                          String methodName, boolean hasBody, String[] pathParams,
                          boolean shard) {
        this.method = method;
        this.path = path;
        this.beanName = beanName;
        this.methodName = methodName;
        this.hasBody = hasBody;
        this.pathParams = pathParams;
        this.shard = shard;
    }

    public String   method()     { return method; }
    public String   path()       { return path; }
    public String   beanName()   { return beanName; }
    public String   methodName() { return methodName; }
    public boolean  hasBody()    { return hasBody; }
    public String[] pathParams() { return pathParams; }
    public boolean  shard()      { return shard; }
}
