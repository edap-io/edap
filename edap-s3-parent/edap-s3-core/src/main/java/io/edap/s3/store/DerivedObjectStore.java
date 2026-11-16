/*
 * Copyright 2026 The edap Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package io.edap.s3.store;

import io.edap.image.ThumbSpec;

import java.io.IOException;

/**
 * 派生资源 store —— 在普通 {@link ObjectStore} 之上叠一层「派生资源」能力。
 *
 * <p><b>派生资源</b>指由已有对象算出来、但本身不是 S3 对象的资源。当前只有一种：
 * 缩略图（{@link ThumbSpec} 指定规格）。它<b>不是</b> S3 协议的一部分，所以：</p>
 * <ul>
 *   <li>不进 {@link io.edap.s3.op.S3Operation} 枚举；</li>
 *   <li>不占桶里的 key 空间，不出现在 LIST 结果里；</li>
 *   <li>走独立的 URL 路径（{@code /thumb/*}），不与 S3 的 {@code /*} 抢路径。</li>
 * </ul>
 *
 * <p><b>装饰器语义</b>：实现类把被装饰的 {@code ObjectStore} 原样透传
 * （put/get/head/delete/list/isEmpty 全部转发），只在上面叠派生资源的读写。
 * 调用方拿到的仍然是一个完整的 {@code ObjectStore}，多出来的只是
 * {@link #getDerived}。</p>
 *
 * <p><b>为什么是异步派生</b>：HTTP handler 跑在 NIO selector 线程上
 * （{@code DisruptorReadDispatcher → Http1Connection}），缩略图生成是百毫秒级的
 * 像素运算 + 子进程，做在请求线程上会把整个连接层卡住。所以未命中时
 * {@link #getDerived} 只<b>提交</b>派生任务并立刻返回，由调用方回 202 让客户端重试。</p>
 */
public interface DerivedObjectStore extends ObjectStore {

    /**
     * 读派生资源。
     *
     * <p>语义分三种：</p>
     * <ol>
     *   <li><b>命中</b> ⇒ 返回派生内容字节；</li>
     *   <li><b>未命中</b> ⇒ 提交异步派生任务，返回 {@code null}；</li>
     *   <li><b>源对象不存在</b> ⇒ 抛 {@link io.edap.s3.error.S3Exception}
     *       （{@code NO_SUCH_KEY} / {@code NO_SUCH_BUCKET}）。</li>
     * </ol>
     *
     * <p>未命中返回 {@code null} 而不是抛异常，是因为「还没算出来」不是错误 ——
     * 调用方据此回 202 Accepted + Retry-After，客户端稍后重试即可。</p>
     *
     * <p>实现必须保证：同一个 (bucket, key, spec) 的并发请求<b>不会</b>重复提交
     * 派生任务（否则一次刷库会拉起 N 份相同的子进程）。</p>
     *
     * @param bucket 桶名
     * @param key    源对象 key
     * @param spec   派生规格
     * @return 命中时的派生内容；未命中时 {@code null}
     * @throws IOException 读源对象失败
     */
    byte[] getDerived(String bucket, String key, ThumbSpec spec) throws IOException;
}
