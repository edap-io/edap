package io.edap.container.app;

import java.util.List;

public final class GrpcRouteEntry {
    private final String                serviceName; // "helloworld.Greeter"
    private final List<GrpcMethodEntry> methods;

    public GrpcRouteEntry(String serviceName, List<GrpcMethodEntry> methods) {
        this.serviceName = serviceName;
        this.methods = methods;
    }

    public String                serviceName() { return serviceName; }
    public List<GrpcMethodEntry> methods()     { return methods; }


    public final class GrpcMethodEntry {
        private final String  methodName;     // "SayHello"（PB 描述里的方法名）
        private final String  javaMethodName; // "sayHello"（bean 上的 Java 方法名）
        private final String  reqDesc;        // 请求体 PB 描述的 FQCN（保持与 HttpRouteEntry 同样的"扫描期纯 String"原则）
        private final String  respDesc;       // 响应体 PB 描述的 FQCN
        private final boolean shard;          // true = 此 gRPC 方法 shard 亲和（@ShardKey 标注），handler dispatch 走 ShardRegistry

        public GrpcMethodEntry(String methodName, String javaMethodName,
                               String reqDesc, String respDesc, boolean shard) {
            this.methodName = methodName;
            this.javaMethodName = javaMethodName;
            this.reqDesc = reqDesc;
            this.respDesc = respDesc;
            this.shard = shard;
        }

        public String  methodName()     { return methodName; }
        public String  javaMethodName() { return javaMethodName; }
        public String  reqDesc()        { return reqDesc; }
        public String  respDesc()       { return respDesc; }
        public boolean shard()          { return shard; }
    }
}