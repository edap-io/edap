package io.edap.s3.starter;

import io.edap.http.WSHandler;
import io.edap.http.server.UrlMapping;
import io.edap.http.server.UrlMappingItem;
import io.edap.log.Logger;
import io.edap.log.LoggerManager;
import io.edap.s3.auth.*;
import io.edap.s3.http.S3Dispatcher;
import io.edap.s3.http.S3HttpHandler;
import io.edap.s3.http.ThumbHttpHandler;
import io.edap.s3.op.handler.*;
import io.edap.s3.store.BucketStore;
import io.edap.s3.store.DerivedObjectStore;
import io.edap.s3.store.MultipartStore;
import io.edap.s3.store.ObjectStore;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

/**
 * S3 的 URL 映射 —— 把 HTTP 路径绑到 S3 协议处理器上。
 *
 * <p><b>两族路径</b>：
 * <ul>
 *   <li>{@code /*} —— S3 协议本体（path-style）；</li>
 *   <li>{@code /thumb/*} —— 缩略图（派生资源，<b>不是</b> S3 协议的一部分）。</li>
 * </ul>
 *
 * <p>两族能共存是因为 edap 的 postfix wildcard 路由按<b>模式串长度降序</b>匹配
 * （{@code PostfixWildcardPathRouter}）—— {@code /thumb/*} 比 {@code /*} 长，
 * 所以 {@code /thumb/...} 走缩略图处理器，其余走 S3。
 * 副作用：名为 {@code thumb} 的桶的<b>对象</b>路径会被缩略图处理器接管。</p>
 *
 * <p><b>store 从哪来</b>：构造注入。store 的创建、启动期加载（索引 log 回放、
 * 老布局迁移、multipart 崩溃恢复）都在 {@code Config} 的 {@code @Bean} 方法里做 ——
 * 那些是一次性的启动期动作，不该在每次 {@link #urlMappings()} 被调用时重跑。</p>
 */
public class S3UrlMapping implements UrlMapping {

    static Logger log = LoggerManager.getLogger(S3UrlMapping.class);

    private static final String AKID = "AKIDEXAMPLE";
    private static final String SECRET = "wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY";
    private static final String REGION = "us-east-1";

    private final BucketStore bucketStore;
    private final ObjectStore objectStore;
    private final MultipartStore multipartStore;
    /** 派生资源 store —— 可为 null（不提供缩略图）。 */
    private final DerivedObjectStore derivedObjectStore;

    private SigV4Presigner presigner;
    private SigV4Verifier verifier;
    private AccessKeyResolver resolver;

    /**
     * @param bucketStore        桶 store
     * @param objectStore        对象 store
     * @param multipartStore     multipart store
     * @param derivedObjectStore 派生资源 store；传 null 则不注册 {@code /thumb/*}
     */
    public S3UrlMapping(BucketStore bucketStore,
                        ObjectStore objectStore,
                        MultipartStore multipartStore,
                        DerivedObjectStore derivedObjectStore) {
        this.bucketStore = bucketStore;
        this.objectStore = objectStore;
        this.multipartStore = multipartStore;
        this.derivedObjectStore = derivedObjectStore;
        Properties p = new Properties();
        p.setProperty("s3.accessKey." + AKID + ".secret", SECRET);
        p.setProperty("s3.accessKey." + AKID + ".region", REGION);
        resolver = ConfigAccessKeyResolver.fromProperties(p);
        presigner = new SigV4Presigner();
        verifier = new SigV4Verifier(resolver);
    }

    @Override
    public Map<String, UrlMappingItem> urlMappings() {
        S3Dispatcher dispatcher = new S3Dispatcher();

        S3AuthVerifier authVerifier = buildAuthVerifier();

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

        // ===== 派生资源（缩略图）=====
        // /thumb/* 比 /* 长 ⇒ 路由时优先匹配。注册 GET + HEAD 两个 method ——
        // 没注册的 method 会被框架回 405。
        if (derivedObjectStore != null) {
            ThumbHttpHandler thumbHandler = new ThumbHttpHandler(derivedObjectStore);
            items.put("GET:/thumb/*", new UrlMappingItem("/thumb/*", "GET", thumbHandler));
            items.put("HEAD:/thumb/*", new UrlMappingItem("/thumb/*", "HEAD", thumbHandler));
        }
        return items;
    }

    /**
     * 解析 S3 数据根 —— 系统属性 {@code s3.root.path} &gt; 环境变量同名项 &gt;
     * 工作目录。
     *
     * <p>{@code Config} 在 {@code io.edap.s3.starter.conf} 包里 ⇒ 必须 public。</p>
     */
    public static Path resolveRoot() {
        String rootPath = System.getProperty("s3.root.path");
        if (rootPath == null || rootPath.isEmpty()) {
            rootPath = System.getenv("s3.root.path");
        }
        if (rootPath == null || rootPath.isEmpty()) {
            rootPath = "./";
        }
        String frootPath = rootPath;
        log.info("s3.root.path:[{}]", l-> l.arg(frootPath));
        return Path.of(rootPath);
    }

    /**
     * 构造鉴权链 —— <b>必须用主 store 用的那个 bucketStore</b>。
     *
     * <p>桶 ACL 存在 {@code <root>/<bucket>/.acl}。root 不一致 ⇒ 读不到那个文件:
     * <ul>
     *   <li>桶目录不存在 ⇒ {@code getCannedAcl} 抛 NoSuchBucket ⇒ 404</li>
     *   <li>桶目录存在但没有 {@code .acl} ⇒ 默认 PRIVATE ⇒ 匿名读被 403 拒掉</li>
     * </ul>
     * 线上踩过:ACL 校验器曾硬编码工作目录 {@code ./},而主 store 用
     * {@code s3.root.path} ⇒ 数据根与工作目录分开时匿名读全线 403。
     */
    S3AuthVerifier buildAuthVerifier() {
        return new BucketPolicyAwareVerifier(new SigV4Verifier(resolver), bucketStore);
    }

    @Override
    public Map<String, WSHandler> websocketMapping() {
        return Map.of();
    }
}
