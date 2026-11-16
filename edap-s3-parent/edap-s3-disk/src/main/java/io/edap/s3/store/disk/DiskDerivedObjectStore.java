/*
 * Copyright 2026 The edap Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package io.edap.s3.store.disk;

import io.edap.image.ImageEngine;
import io.edap.image.ThumbSpec;
import io.edap.log.Logger;
import io.edap.log.LoggerManager;
import io.edap.s3.error.S3ErrorCode;
import io.edap.s3.error.S3Exception;
import io.edap.s3.model.GetStream;
import io.edap.s3.model.ListOptions;
import io.edap.s3.model.ListResult;
import io.edap.s3.model.ObjectMeta;
import io.edap.s3.model.PutStream;
import io.edap.s3.store.DerivedObjectStore;
import io.edap.s3.store.ObjectStore;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

/**
 * 磁盘派生资源 store —— 装饰器：把被装饰的 {@link ObjectStore} 原样透传，
 * 在上面叠一层「缩略图」能力。
 *
 * <p><b>缓存布局</b>（{@code derivedRoot} = {@code <s3数据根>/_derived}）：
 * <pre>
 *   _derived/thumb/&lt;engineTag&gt;/&lt;specTag&gt;/&lt;bucket&gt;/&lt;key&gt;        派生内容
 *   _derived/thumbmeta/&lt;engineTag&gt;/&lt;specTag&gt;/&lt;bucket&gt;/&lt;key&gt;.etag  源图 ETag sidecar
 * </pre>
 *
 * <p><b>为什么内容与 sidecar 是两棵平行的树</b>：sidecar 必须在内容旁边才能做到
 * 「写完内容才写 sidecar」的崩溃安全（内容在、sidecar 不在 ⇒ 当作未命中重算）。
 * 但如果 sidecar 就叫 {@code <key>.meta} 放在同一棵树里，源 key {@code a} 的 sidecar
 * （{@code a.meta}）会跟源 key {@code a.meta} 的内容文件撞名。分成两棵树之后，
 * sidecar 一律加 {@code .etag} 后缀 ⇒ 映射是单射的（{@code a} → {@code a.etag}，
 * {@code a.etag} → {@code a.etag.etag}），内容树与 sidecar 树也不会互相撞。</p>
 *
 * <p><b>为什么缓存 key 要带引擎标识</b>：换引擎（libvips → ImageMagick）会改变输出字节，
 * 但 URL 和规格没变 —— 不带引擎标识的话旧缓存会被当成新引擎的产物继续用。
 * {@code <engineTag>} 取自 {@link ImageEngine#id()}。</p>
 *
 * <p><b>陈旧判定</b>：sidecar 里记的是源对象的 ETag。读缓存时拿它跟当前源对象的
 * ETag 比 —— 不一致说明源图被替换过 ⇒ 当作未命中重算。这样源图的删除/覆盖都不需要
 * 在写路径上做失效处理（写路径拿不到「谁被覆盖了」的信息）。</p>
 *
 * <p><b>为什么派生是异步的</b>：HTTP handler 跑在 NIO selector 线程上，
 * 缩略图是百毫秒级的像素运算 + 子进程，做在请求线程上会把整个连接层卡住。
 * 所以未命中时只提交任务并立刻返回 {@code null}，由 HTTP 层回 202 让客户端重试。</p>
 *
 * <p><b>线程安全</b>：本类可被多个 HTTP 工作线程并发调用。</p>
 */
public final class DiskDerivedObjectStore implements DerivedObjectStore {

    private static final Logger log = LoggerManager.getLogger(DiskDerivedObjectStore.class);

    /** 派生内容子树名。 */
    static final String BODY_DIR = "thumb";

    /** 源图 ETag sidecar 子树名 —— 与内容树平行，见类注释。 */
    static final String META_DIR = "thumbmeta";

    /** sidecar 文件后缀。 */
    static final String ETAG_SUFFIX = ".etag";

    /** 派生工作线程数。 */
    public static final int DEFAULT_WORKERS = 2;

    /** 派生任务队列容量。 */
    public static final int DEFAULT_QUEUE_CAPACITY = 256;

    /** 源对象字节数上限 —— 超过就不派生（缩略图不需要拿超大原图）。 */
    public static final long DEFAULT_MAX_SOURCE_BYTES = 32L * 1024 * 1024;

    private final ObjectStore delegate;
    private final Path derivedRoot;
    private final ImageEngine engine;
    private final long maxSourceBytes;
    private final ThreadPoolExecutor workers;
    /** 正在派生中的任务 key（缓存文件全路径）—— 防止同一张图被并发派生多次。 */
    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();

    public DiskDerivedObjectStore(ObjectStore delegate, Path derivedRoot, ImageEngine engine) {
        this(delegate, derivedRoot, engine, DEFAULT_WORKERS, DEFAULT_QUEUE_CAPACITY,
                DEFAULT_MAX_SOURCE_BYTES);
    }

    public DiskDerivedObjectStore(ObjectStore delegate,
                                  Path derivedRoot,
                                  ImageEngine engine,
                                  int workerThreads,
                                  int queueCapacity,
                                  long maxSourceBytes) {
        if (delegate == null) {
            throw new IllegalArgumentException("delegate 不能为 null");
        }
        if (derivedRoot == null) {
            throw new IllegalArgumentException("derivedRoot 不能为 null");
        }
        if (engine == null) {
            throw new IllegalArgumentException("engine 不能为 null");
        }
        if (workerThreads <= 0 || queueCapacity <= 0) {
            throw new IllegalArgumentException("workerThreads / queueCapacity 必须大于 0");
        }
        this.delegate = delegate;
        this.derivedRoot = derivedRoot.toAbsolutePath();
        this.engine = engine;
        this.maxSourceBytes = maxSourceBytes;
        this.workers = newWorkerPool(workerThreads, queueCapacity);
    }

    // ================= 派生资源 =================

    @Override
    public byte[] getDerived(String bucket, String key, ThumbSpec spec) throws IOException {
        if (bucket == null || bucket.isEmpty()) {
            throw invalidArgument("bucket 不能为空", bucket, null);
        }
        if (key == null || key.isEmpty()) {
            throw invalidArgument("key 不能为空", bucket, key);
        }
        if (spec == null) {
            throw invalidArgument("spec 不能为 null", bucket, key);
        }
        checkPathSafe(bucket, key);

        // 源对象不存在 ⇒ NO_SUCH_KEY / NO_SUCH_BUCKET 冒泡（调用方回 404）
        ObjectMeta meta = delegate.head(bucket, key);

        Path body = bodyPath(bucket, key, spec);
        byte[] cached = readFresh(body, etagPath(bucket, key, spec), meta.etag());
        if (cached != null) {
            return cached;
        }
        submitDerivation(bucket, key, spec, meta);
        return null;
    }

    /**
     * 读缓存 —— 内容与 sidecar 都在、且 sidecar 记的 ETag 与当前源图一致才算命中。
     */
    private byte[] readFresh(Path body, Path etagFile, String sourceEtag) throws IOException {
        if (!Files.isRegularFile(body)) {
            return null;
        }
        String recorded;
        try {
            recorded = Files.readString(etagFile, StandardCharsets.UTF_8).trim();
        } catch (NoSuchFileException e) {
            // 内容在、sidecar 不在 ⇒ 上次派生没写完 ⇒ 当作未命中（重算会补上 sidecar）
            return null;
        }
        if (recorded.isEmpty() || !recorded.equals(sourceEtag)) {
            // 源图换过 ⇒ 陈旧
            return null;
        }
        return Files.readAllBytes(body);
    }

    /**
     * 提交派生任务。同一个 (bucket, key, spec) 已有任务在跑时直接跳过 ——
     * 否则一次刷库会拉起 N 份相同的子进程。
     */
    private void submitDerivation(String bucket, String key, ThumbSpec spec, ObjectMeta meta)
            throws IOException {
        if (!engine.available()) {
            throw new S3Exception(S3ErrorCode.SERVICE_UNAVAILABLE,
                    "缩略图引擎不可用: " + engine.id(), "/" + bucket + "/" + key);
        }
        if (meta.size() > maxSourceBytes) {
            throw new S3Exception(S3ErrorCode.INVALID_ARGUMENT,
                    "源对象过大，无法派生: " + meta.size() + " > " + maxSourceBytes,
                    "/" + bucket + "/" + key);
        }
        final String taskKey = bodyPath(bucket, key, spec).toString();
        if (!inFlight.add(taskKey)) {
            return;
        }
        try {
            workers.execute(() -> {
                try {
                    derive(bucket, key, spec, meta.etag());
                } catch (Throwable t) {
                    log.warn("派生缩略图失败: " + bucket + "/" + key + " " + spec, t);
                } finally {
                    inFlight.remove(taskKey);
                }
            });
        } catch (RejectedExecutionException e) {
            inFlight.remove(taskKey);
            throw new S3Exception(S3ErrorCode.SERVICE_UNAVAILABLE,
                    "派生队列已满，请稍后重试", "/" + bucket + "/" + key);
        }
    }

    /**
     * 派生流程 —— 读源图 → 交给引擎 → 落盘。
     *
     * <p><b>写入顺序</b>：先写内容（临时文件 + 原子 rename），再写 sidecar。
     * 中途崩溃 ⇒ 内容在、sidecar 不在 ⇒ 读侧当未命中重算 ⇒ 不会读到半成品。</p>
     */
    private void derive(String bucket, String key, ThumbSpec spec, String sourceEtag)
            throws IOException {
        byte[] source = readSource(bucket, key);
        byte[] thumb = engine.thumbnail(source, spec);

        Path body = bodyPath(bucket, key, spec);
        Path etagFile = etagPath(bucket, key, spec);
        Files.createDirectories(body.getParent());
        Files.createDirectories(etagFile.getParent());
        writeAtomic(body, thumb);
        writeAtomic(etagFile, sourceEtag.getBytes(StandardCharsets.UTF_8));
    }

    private byte[] readSource(String bucket, String key) throws IOException {
        GetStream stream = delegate.get(bucket, key, 0, -1);
        try (InputStream in = stream.content()) {
            return in.readAllBytes();
        }
    }

    // ================= 缓存路径 =================

    Path bodyPath(String bucket, String key, ThumbSpec spec) {
        return derivedRoot.resolve(BODY_DIR)
                .resolve(engine.id())
                .resolve(specTag(spec))
                .resolve(bucket)
                .resolve(key);
    }

    Path etagPath(String bucket, String key, ThumbSpec spec) {
        return derivedRoot.resolve(META_DIR)
                .resolve(engine.id())
                .resolve(specTag(spec))
                .resolve(bucket)
                .resolve(key + ETAG_SUFFIX);
    }

    /**
     * 规格的目录名 —— 缓存 key 的一部分。
     *
     * <p>含全部影响输出字节的维度：宽、高、格式、质量。引擎标识不在这里 ——
     * 它是 {@code bodyPath} 的上一层目录。</p>
     */
    static String specTag(ThumbSpec spec) {
        StringBuilder sb = new StringBuilder(32);
        sb.append('w').append(spec.width());
        if (spec.height() != null) {
            sb.append("-h").append(spec.height());
        }
        sb.append("-f").append(spec.format().name().toLowerCase(Locale.ROOT));
        sb.append("-q").append(spec.quality());
        return sb.toString();
    }

    /**
     * 桶名 / key 不能逃出缓存根 —— 它们会被拼进文件路径。
     */
    private static void checkPathSafe(String bucket, String key) {
        if (bucket.contains("/") || bucket.equals("..") || bucket.equals(".")) {
            throw invalidArgument("bucket 名非法", bucket, key);
        }
        if (key.startsWith("/")) {
            throw invalidArgument("key 必须是相对路径", bucket, key);
        }
        for (String seg : key.split("/", -1)) {
            if (seg.equals("..")) {
                throw invalidArgument("key 不能包含 '..'", bucket, key);
            }
        }
    }

    private static S3Exception invalidArgument(String why, String bucket, String key) {
        return new S3Exception(S3ErrorCode.INVALID_ARGUMENT, why,
                "/" + (bucket == null ? "" : bucket) + "/" + (key == null ? "" : key));
    }

    private static void writeAtomic(Path target, byte[] data) throws IOException {
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.write(tmp, data);
        try {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    // ================= 装饰器：ObjectStore 全部透传 =================

    @Override
    public ObjectMeta put(String bucket, String key, PutStream body) throws IOException {
        return delegate.put(bucket, key, body);
    }

    @Override
    public GetStream get(String bucket, String key, long rangeStart, long rangeEnd)
            throws IOException {
        return delegate.get(bucket, key, rangeStart, rangeEnd);
    }

    @Override
    public ObjectMeta head(String bucket, String key) throws IOException {
        return delegate.head(bucket, key);
    }

    @Override
    public void delete(String bucket, String key) throws IOException {
        delegate.delete(bucket, key);
        // 源图没了，派生缓存就是孤儿 —— 清掉，别让它一直占空间
        deleteDerived(bucket, key);
    }

    @Override
    public ListResult list(String bucket, ListOptions options) throws IOException {
        return delegate.list(bucket, options);
    }

    @Override
    public boolean isEmpty(String bucket) throws IOException {
        return delegate.isEmpty(bucket);
    }

    @Override
    public void init() throws IOException {
        delegate.init();
    }

    @Override
    public void forgetBucket(String bucket) {
        delegate.forgetBucket(bucket);
    }

    @Override
    public void close() throws IOException {
        workers.shutdownNow();
        delegate.close();
    }

    /**
     * 清掉某个源对象的所有派生缓存 —— 遍历「引擎标识 × 规格」两层目录，
     * 逐个删内容文件与 sidecar。
     */
    private void deleteDerived(String bucket, String key) {
        Path bodyRoot = derivedRoot.resolve(BODY_DIR);
        Path metaRoot = derivedRoot.resolve(META_DIR);
        if (!Files.isDirectory(bodyRoot)) {
            return;
        }
        try (Stream<Path> engineDirs = Files.list(bodyRoot)) {
            for (Path engineDir : (Iterable<Path>) engineDirs::iterator) {
                if (!Files.isDirectory(engineDir)) {
                    continue;
                }
                try (Stream<Path> specDirs = Files.list(engineDir)) {
                    for (Path specDir : (Iterable<Path>) specDirs::iterator) {
                        if (!Files.isDirectory(specDir)) {
                            continue;
                        }
                        deleteQuietly(specDir.resolve(bucket).resolve(key));
                        deleteQuietly(metaRoot.resolve(engineDir.getFileName())
                                .resolve(specDir.getFileName())
                                .resolve(bucket)
                                .resolve(key + ETAG_SUFFIX));
                    }
                }
            }
        } catch (IOException e) {
            log.warn("清理派生缓存失败: " + bucket + "/" + key, e);
        }
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignore) {
            // 删不掉不影响主流程 —— 顶多是下次读侧当陈旧重算
        }
    }

    // ================= 工作池 =================

    private static ThreadPoolExecutor newWorkerPool(int threads, int queueCapacity) {
        AtomicInteger seq = new AtomicInteger();
        ThreadFactory factory = r -> {
            Thread t = new Thread(r, "s3-thumb-derive-" + seq.incrementAndGet());
            // daemon：容器停的时候不能因为派生线程挂着而走不掉
            t.setDaemon(true);
            return t;
        };
        ThreadPoolExecutor pool = new ThreadPoolExecutor(threads, threads, 60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(queueCapacity), factory);
        pool.allowCoreThreadTimeOut(true);
        return pool;
    }

    /** 便于测试观察内部状态。 */
    List<String> inflightKeys() {
        return List.copyOf(inFlight);
    }

    /** 引擎标识 —— 缓存目录名的一部分。 */
    String engineId() {
        return engine.id();
    }

    /** 派生根目录。 */
    Path derivedRoot() {
        return derivedRoot;
    }
}
