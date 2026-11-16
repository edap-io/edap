/*
 * Copyright 2023 The edap Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package io.edap.erpc.client;

/**
 * eRPC 客户端的参数。
 */
public final class ErpcClientOptions {

    /** 每个节点的连接数（连接池大小）。 */
    private int poolSize = 4;
    /** 连接超时（毫秒）。 */
    private int connectTimeoutMillis = 5_000;
    /** 调用的默认超时（毫秒）。 */
    private long callTimeoutMillis = 30_000;

    public static ErpcClientOptions defaults() {
        return new ErpcClientOptions();
    }

    /**
     * 每个节点保持多少条长连接。
     *
     * <p>★ <b>为什么默认是 4 而不是 1</b>：一条连接上多个请求可以多路复用
     * （靠 requestId 配对），但 socket 的写是串行的 —— 高并发下 1 条连接会变成
     * 写瓶颈。连接池让并发调用分散到多条 socket 上。</p>
     */
    public ErpcClientOptions poolSize(int poolSize) {
        if (poolSize <= 0) {
            throw new IllegalArgumentException("连接池大小必须为正: " + poolSize);
        }
        this.poolSize = poolSize;
        return this;
    }

    public ErpcClientOptions connectTimeoutMillis(int connectTimeoutMillis) {
        this.connectTimeoutMillis = connectTimeoutMillis;
        return this;
    }

    public ErpcClientOptions callTimeoutMillis(long callTimeoutMillis) {
        this.callTimeoutMillis = callTimeoutMillis;
        return this;
    }

    public int getPoolSize() {
        return poolSize;
    }

    public int getConnectTimeoutMillis() {
        return connectTimeoutMillis;
    }

    public long getCallTimeoutMillis() {
        return callTimeoutMillis;
    }
}
