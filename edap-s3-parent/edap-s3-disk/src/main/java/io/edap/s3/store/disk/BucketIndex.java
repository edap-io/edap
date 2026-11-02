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

import io.edap.s3.model.ListOptions;
import io.edap.s3.model.ListResult;
import io.edap.s3.model.ObjectMeta;
import io.edap.s3.store.ListPaging;
import io.edap.s3.store.Utf8KeyOrder;

import java.io.Closeable;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * per-bucket 的内存索引 —— 把 {@link IndexLog} 里的记录回放成一张
 * <b>按键序排列</b>的表。
 *
 * <p>排序用 {@link Utf8KeyOrder}(UTF-8 字节序 == code point 序),不是
 * {@code String.compareTo}(代理对上两者相反)。
 *
 * <p><b>锁</b>:整个索引只有一把锁(per-bucket,不是全局一把),只保护
 * 「log append + 内存表更新」这两步的原子性;读路径(HEAD / LIST / isEmpty)
 * 完全无锁。
 *
 * <p><b>为什么 append 和 map 更新必须在同一把锁里</b>:两步之间到达的 LIST
 * 会看到「log 里有、map 里没有」的对象(刚 PUT 完立刻 LIST 少一个)。
 * 同锁 ⇒ 对读者而言二者始终一致。
 *
 * <p><b>顺序</b>:先 {@code log.append} 再 {@code map.put}。反过来(先改内存
 * 后写 log)若中途崩溃,重启后对象会消失 ⇒ 数据丢失;先 log 后 map 则最坏只
 * 是内存少一条,重启从 log 重建即可。
 */
final class BucketIndex implements Closeable {

    private final ConcurrentSkipListMap<String, ObjectMeta> map
            = new ConcurrentSkipListMap<>(Utf8KeyOrder.INSTANCE);
    private final ReentrantLock lock = new ReentrantLock();
    private final IndexLog log;

    private BucketIndex(IndexLog log) {
        this.log = log;
    }

    /**
     * 打开索引并回放 log —— 内存表 = log 的最终状态。
     */
    static BucketIndex load(IndexLog log) throws IOException {
        BucketIndex index = new BucketIndex(log);
        List<IndexRecord> records = log.replay();
        for (IndexRecord rec : records) {
            if (rec.op() == IndexRecord.Op.PUT) {
                ObjectMeta meta = rec.toMeta();
                index.map.put(meta.key(), meta);
            } else {
                index.map.remove(rec.key());
            }
        }
        return index;
    }

    // ================= 读路径(无锁) =================

    ObjectMeta head(String key) {
        return map.get(key);
    }

    ListResult list(ListOptions options) {
        return ListPaging.paginate(map, options, map::get);
    }

    boolean isEmpty() {
        return map.isEmpty();
    }

    int size() {
        return map.size();
    }

    /** 索引 log 的字节数(启动期压缩判定用)。 */
    long logSize() {
        return log.size();
    }

    // ================= 写路径(持锁) =================

    /**
     * 写入对象元数据 —— 先提交 log,再更新内存表。
     */
    void put(ObjectMeta meta) throws IOException {
        lock.lock();
        try {
            log.append(IndexRecord.putOf(meta));
            map.put(meta.key(), meta);
        } finally {
            lock.unlock();
        }
    }

    /**
     * 删除对象 —— 先提交 log(对象消失),再更新内存表。
     * key 不存在时直接返回(S3 DELETE 幂等,也不往 log 里塞 no-op 记录)。
     */
    void delete(String key) throws IOException {
        lock.lock();
        try {
            if (!map.containsKey(key)) return;
            log.append(IndexRecord.delete(key));
            map.remove(key);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void close() throws IOException {
        lock.lock();
        try {
            log.close();
        } finally {
            lock.unlock();
        }
    }
}
