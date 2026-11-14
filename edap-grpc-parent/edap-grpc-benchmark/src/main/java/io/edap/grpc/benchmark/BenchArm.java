/*
 * Copyright 2026 The edap Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package io.edap.grpc.benchmark;

import java.util.function.IntFunction;

/**
 * 基准的一个「臂」—— <b>客户端框架 × 服务端框架</b>的组合。
 *
 * <p>为什么要有臂这个概念：只测「edap 客户端 → edap 服务端」与「grpc-java 客户端 → grpc-java 服务端」
 * 这两条链路时，客户端与服务端是<b>同时</b>换掉的，测出来的差距无法归因 ——
 * 是客户端慢？还是服务端慢？还是两者都慢？</p>
 *
 * <p>2×2 矩阵把两个维度拆开：</p>
 * <table border="1">
 *   <caption>四个臂</caption>
 *   <tr><th></th><th>edap 服务端</th><th>grpc-java 服务端</th></tr>
 *   <tr><td>edap 客户端</td><td>E→E</td><td>E→G</td></tr>
 *   <tr><td>grpc-java 客户端</td><td>G→E</td><td>G→G</td></tr>
 * </table>
 *
 * <ul>
 *   <li><b>同服务端换客户端</b>（E→E vs G→E）⇒ 客户端这一侧的贡献；</li>
 *   <li><b>同客户端换服务端</b>（E→E vs E→G）⇒ 服务端这一侧的贡献；</li>
 *   <li><b>E→G</b> ⇒ 已知双方都能互通（{@code checkInterop} 会先验），所以这一格不是「能不能通」
 *       的问题，而是「通了以后谁快」。</li>
 * </ul>
 *
 * <p>每个臂<b>独占一条连接</b> —— 测的是单臂的表现，不能让别的臂的流量挤进来。</p>
 */
public final class BenchArm implements AutoCloseable {

    /** 标签（{@code E→E} / {@code E→G} / {@code G→E} / {@code G→G}）。 */
    public final String label;

    /** 客户端框架名（{@code edap} / {@code google}）。 */
    public final String clientName;

    /** 服务端框架名（{@code edap} / {@code google}）。 */
    public final String serverName;

    /** 这个臂的客户端连接。 */
    private final AutoCloseable client;

    /** 按负载大小造出「一次调用」的动作。 */
    private final IntFunction<BenchRunner.Op> opFactory;

    BenchArm(String label, String clientName, String serverName,
             AutoCloseable client, IntFunction<BenchRunner.Op> opFactory) {
        this.label = label;
        this.clientName = clientName;
        this.serverName = serverName;
        this.client = client;
        this.opFactory = opFactory;
    }

    /**
     * 造出这个臂在给定负载下的调用动作。
     *
     * <p>★ 每个臂用自己的消息类型（edap 客户端用 {@link Payload}，grpc-java 客户端用
     * {@link Bench.Payload}）—— wire 格式一致，语义等价，但走各自框架的编解码器，
     * 这正是要测的东西。</p>
     *
     * @param blobSize blob 的字节数
     */
    public BenchRunner.Op op(int blobSize) {
        return opFactory.apply(blobSize);
    }

    @Override
    public void close() throws Exception {
        client.close();
    }
}
