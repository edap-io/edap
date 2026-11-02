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

import io.edap.s3.model.ObjectMeta;

import java.time.Instant;
import java.util.Collections;
import java.util.Map;

/**
 * 索引 log 里的一条记录 —— 两种:
 * <ul>
 *   <li>{@link Op#PUT}:对象存在,带完整元数据</li>
 *   <li>{@link Op#DELETE}:对象消失</li>
 * </ul>
 *
 * <p>DELETE 记录只记 key(不记 body 信息)—— 回放时从索引里移除该 key。
 *
 * <p>不可变;编解码见 {@link IndexCodec}。
 */
public final class IndexRecord {

    /** 记录类型。 */
    public enum Op {
        /** 对象写入(或覆盖)。 */
        PUT('P'),
        /** 对象删除。 */
        DELETE('D');

        private final char code;

        Op(char code) {
            this.code = code;
        }

        public char code() {
            return code;
        }

        static Op fromCode(char c) {
            for (Op op : values()) {
                if (op.code == c) return op;
            }
            return null;
        }
    }

    private final Op op;
    private final String key;
    private final String etag;              // PUT:MD5 hex,null = 无
    private final long size;                // PUT
    private final String contentType;       // PUT:null = 无
    private final long lastModifiedMillis;  // PUT
    private final Map<String, String> userMetadata;  // PUT:不可为 null

    private IndexRecord(Op op,
                        String key,
                        String etag,
                        long size,
                        String contentType,
                        long lastModifiedMillis,
                        Map<String, String> userMetadata) {
        this.op = op;
        this.key = key;
        this.etag = etag;
        this.size = size;
        this.contentType = contentType;
        this.lastModifiedMillis = lastModifiedMillis;
        this.userMetadata = userMetadata == null
                ? Collections.emptyMap()
                : Collections.unmodifiableMap(userMetadata);
    }

    public static IndexRecord put(String key,
                                  String etag,
                                  long size,
                                  String contentType,
                                  long lastModifiedMillis,
                                  Map<String, String> userMetadata) {
        return new IndexRecord(Op.PUT, key, etag, size, contentType,
                lastModifiedMillis, userMetadata);
    }

    public static IndexRecord delete(String key) {
        return new IndexRecord(Op.DELETE, key, null, 0, null, 0, null);
    }

    /** 由 {@link ObjectMeta} 生成一条 PUT 记录。 */
    public static IndexRecord putOf(ObjectMeta meta) {
        Instant lm = meta.lastModified();
        return new IndexRecord(Op.PUT, meta.key(), meta.etag(), meta.size(),
                meta.contentType(), lm == null ? 0 : lm.toEpochMilli(),
                meta.userMetadata());
    }

    public Op op() {
        return op;
    }

    public String key() {
        return key;
    }

    public String etag() {
        return etag;
    }

    public long size() {
        return size;
    }

    public String contentType() {
        return contentType;
    }

    public long lastModifiedMillis() {
        return lastModifiedMillis;
    }

    public Map<String, String> userMetadata() {
        return userMetadata;
    }

    /** 还原成 {@link ObjectMeta}(DELETE 记录没有元数据,返回 null)。 */
    public ObjectMeta toMeta() {
        if (op != Op.PUT) return null;
        return new ObjectMeta(key, size, etag, contentType,
                Instant.ofEpochMilli(lastModifiedMillis), userMetadata);
    }

    @Override
    public String toString() {
        return op.code() + " " + key;
    }
}
