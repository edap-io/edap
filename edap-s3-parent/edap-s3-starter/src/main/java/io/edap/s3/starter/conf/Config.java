package io.edap.s3.starter.conf;

import io.edap.image.ImageEngine;
import io.edap.image.vips.VipsCliImageEngine;
import io.edap.log.Logger;
import io.edap.log.LoggerManager;
import io.edap.microservice.annotation.Bean;
import io.edap.microservice.annotation.Configuration;
import io.edap.s3.starter.S3UrlMapping;
import io.edap.s3.store.disk.DiskBucketStore;
import io.edap.s3.store.disk.DiskDerivedObjectStore;
import io.edap.s3.store.disk.DiskMultipartStore;
import io.edap.s3.store.disk.DiskObjectStore;

import java.io.IOException;
import java.nio.file.Path;

/**
 * S3 应用的装配。
 *
 * <p><b>为什么 store 在这里建而不在 {@link S3UrlMapping} 里建</b>：
 * {@code urlMappings()} 可能被调用多次（版本切换、重绑路由），而 store 的创建 +
 * 启动期加载（索引 log 回放、老布局迁移、multipart 崩溃恢复）都是<b>一次性</b>的动作 ——
 * 放在 {@code @Bean} 方法里做，天然只跑一次，且失败在<b>启动期</b>暴露，
 * 而不是首个请求打到一半才炸。</p>
 *
 * <p><b>为什么参数用具体类型而不是接口</b>：edap 的 {@code @Bean} 工厂方法按类型装配，
 * 而派生 store 也实现了 {@code ObjectStore} ⇒ 用接口注入会命中两个候选直接抛
 * {@code NoUniqueBeanException}。用具体类型（{@code DiskObjectStore} /
 * {@code DiskDerivedObjectStore}）就没有歧义。</p>
 */
@Configuration
public class Config {

    private static final Logger log = LoggerManager.getLogger(Config.class);

    @Bean
    public DiskBucketStore s3BucketStore() throws IOException {
        DiskBucketStore store = new DiskBucketStore(S3UrlMapping.resolveRoot());
        try {
            store.create("user-uploads");
        } catch (Throwable e) {
            log.info("Try create bucket exists", e);
        }
        return store;
    }

    /**
     * 对象 store —— 启动期把 per-bucket 索引 log 加载成内存表，并把老布局
     * （有 {@code .meta} sidecar、没有 {@code .index/log} 的桶）迁移过来。
     * 迁移只做一次，失败要在启动期暴露而不是首个请求。
     */
    @Bean
    public DiskObjectStore s3ObjectStore(DiskBucketStore s3BucketStore) throws IOException {
        DiskObjectStore store = new DiskObjectStore(s3BucketStore);
        store.init();
        return store;
    }

    /**
     * 派生资源 store —— 装饰 {@link #s3ObjectStore}，在上面叠缩略图能力。
     *
     * <p>libvips 是<b>运行期</b>依赖：机器上没装 {@code vips} 命令行时
     * {@link ImageEngine#available()} 返回 false，{@code /thumb/*} 会回 503，
     * S3 本体照常工作。</p>
     */
    @Bean
    public DiskDerivedObjectStore s3DerivedObjectStore(DiskBucketStore s3BucketStore,
                                                       DiskObjectStore s3ObjectStore)
            throws IOException {
        Path derivedRoot = s3BucketStore.root().resolve(DiskBucketStore.DERIVED_DIR);
        ImageEngine engine = new VipsCliImageEngine();
        if (!engine.available()) {
            log.warn("缩略图引擎不可用（引擎 {}）—— /thumb/* 会返回 503，S3 本体不受影响",
                    l -> l.arg(engine.id()));
        }
        return new DiskDerivedObjectStore(s3ObjectStore, derivedRoot, engine);
    }

    @Bean
    public DiskMultipartStore s3MultipartStore(DiskBucketStore s3BucketStore,
                                               DiskDerivedObjectStore s3DerivedObjectStore)
            throws IOException {
        DiskMultipartStore store = new DiskMultipartStore(
                s3BucketStore.root(), s3BucketStore, s3DerivedObjectStore);
        store.startupRecovery();
        return store;
    }

    @Bean
    public S3UrlMapping s3UrlMapping(DiskBucketStore s3BucketStore,
                                     DiskDerivedObjectStore s3DerivedObjectStore,
                                     DiskMultipartStore s3MultipartStore) {
        return new S3UrlMapping(s3BucketStore, s3DerivedObjectStore, s3MultipartStore,
                s3DerivedObjectStore);
    }
}
