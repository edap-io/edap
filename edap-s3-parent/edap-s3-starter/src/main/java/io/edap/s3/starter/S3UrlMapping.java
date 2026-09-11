package io.edap.s3.starter;

import io.edap.http.WSHandler;
import io.edap.http.server.UrlMapping;
import io.edap.http.server.UrlMappingItem;
import io.edap.s3.auth.*;
import io.edap.s3.http.S3Dispatcher;
import io.edap.s3.http.S3HttpHandler;
import io.edap.s3.op.handler.*;
import io.edap.s3.store.BucketStore;
import io.edap.s3.store.MultipartStore;
import io.edap.s3.store.ObjectStore;
import io.edap.s3.store.disk.DiskBucketStore;
import io.edap.s3.store.disk.DiskMultipartStore;
import io.edap.s3.store.disk.DiskObjectStore;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

public class S3UrlMapping implements UrlMapping {

    private static final String AKID = "AKIDEXAMPLE";
    private static final String SECRET = "wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY";
    private static final String REGION = "us-east-1";

    private SigV4Presigner presigner;
    private SigV4Verifier verifier;
    private AccessKeyResolver resolver;

    public S3UrlMapping() {
        Properties p = new Properties();
        p.setProperty("s3.accessKey." + AKID + ".secret", SECRET);
        p.setProperty("s3.accessKey." + AKID + ".region", REGION);
        resolver = ConfigAccessKeyResolver.fromProperties(p);
        presigner = new SigV4Presigner();
        verifier = new SigV4Verifier(resolver);
    }

    @Override
    public Map<String, UrlMappingItem> urlMappings() {
        S3AuthVerifier authVerifier;
        authVerifier = new BucketPolicyAwareVerifier(new SigV4Verifier(resolver), bucketStoreForVerifier());
        S3Dispatcher dispatcher = new S3Dispatcher();

        Path root = Path.of("./");

        try {
            DiskBucketStore bucketStore = new DiskBucketStore(root);
            ObjectStore objectStore = new DiskObjectStore(bucketStore);
            try {
                bucketStore.create("user-uploads");
            } catch (Throwable e) {
                e.printStackTrace();
            }

            MultipartStore multipartStore = new DiskMultipartStore(Path.of("./"), bucketStore, objectStore);

            // ===== 桶级 =====
            dispatcher.register(new ListBucketsHandler(bucketStore));
            dispatcher.register(new CreateBucketHandler(bucketStore));
            dispatcher.register(new DeleteBucketHandler(bucketStore, objectStore));
            dispatcher.register(new HeadBucketHandler(bucketStore));
            dispatcher.register(new ListObjectsV2Handler(bucketStore, objectStore));
            dispatcher.register(new PutBucketAclHandler(bucketStore));
            // ===== 对象级 =====
            dispatcher.register(new PutObjectHandler(bucketStore, objectStore));
            dispatcher.register(new GetObjectHandler(bucketStore, objectStore));
            dispatcher.register(new HeadObjectHandler(bucketStore, objectStore));
            dispatcher.register(new DeleteObjectHandler(bucketStore, objectStore));
            // ===== Multipart =====
            dispatcher.register(new InitiateMultipartHandler(multipartStore));
            dispatcher.register(new UploadPartHandler(multipartStore));
            dispatcher.register(new CompleteMultipartHandler(multipartStore));
            dispatcher.register(new AbortMultipartHandler(multipartStore));
            dispatcher.register(new ListMultipartUploadsHandler(multipartStore));
            dispatcher.register(new ListPartsHandler(multipartStore));

            S3HttpHandler handler = new S3HttpHandler(dispatcher, authVerifier);
            // postfix wildcard 一条规则覆盖所有 S3 路径
            // 每个 HTTP method 单独注册一遍(addPathHandler 按 method 过滤)
            Map<String, UrlMappingItem> items = new HashMap<>();
            items.put("GET:/*", new UrlMappingItem("/*", "GET", handler));
            items.put("PUT:/*", new UrlMappingItem("/*", "PUT", handler));
            items.put("POST:/*", new UrlMappingItem("/*", "POST", handler));
            items.put("DELETE:/*", new UrlMappingItem("/*", "DELETE", handler));
            items.put("HEAD:/*", new UrlMappingItem("/*", "HEAD", handler));
            return items;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * 给 BucketPolicyAwareVerifier 准备一份 bucketStore —— 不能直接复用 urlMappings()
     * 里 create 出来的实例(那个在 try 块里),所以单独开一个轻量字段。
     */
    private BucketStore bucketStoreForVerifier() {
        try {
            return new DiskBucketStore(Path.of("./"));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public Map<String, WSHandler> websocketMapping() {
        return Map.of();
    }
}
