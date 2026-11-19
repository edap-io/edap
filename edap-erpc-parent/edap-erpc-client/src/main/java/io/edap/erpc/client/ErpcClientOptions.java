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
    /**
     * 连接级鉴权凭据 —— 建连后用它发一个 <b>AUTH 帧</b>。
     *
     * <p>★ <b>{@code null} ⇒ 不握手</b>（服务端若开了「必须鉴权」就会拒这条连接）。</p>
     */
    private String credential;

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

    /**
     * 设置<b>连接级</b>鉴权凭据。
     *
     * <p>★ <b>连接级</b>：建连后发一个 AUTH 帧，服务端校验通过后把「已鉴权」
     * 记在那条连接上 ⇒ <b>同一连接的后续请求免校验</b>。</p>
     *
     * <p>★ 与请求级元数据无关：txId 之类走 {@code RpcContext.metadata()}。</p>
     *
     * @param credential 凭据（UTF-8 字符串）；{@code null} ⇒ 不握手
     */
    public ErpcClientOptions credential(String credential) {
        this.credential = credential;
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

    /**
     * 连接级鉴权凭据。
     *
     * @return 凭据；{@code null} ⇒ 不握手
     */
    public String getCredential() {
        return credential;
    }
}
