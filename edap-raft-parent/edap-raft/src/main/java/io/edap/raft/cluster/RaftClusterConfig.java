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

package io.edap.raft.cluster;

import java.util.ArrayList;
import java.util.List;

/**
 * 编排层的配置。
 *
 * <p><b>刻意不依赖 {@code io.edap.props.Props}</b>：raft 模块不应该知道
 * 「配置从哪来」。容器侧把 {@code container.raft.*} 映射成这个对象，
 * 测试里直接 new 一个。</p>
 *
 * <p>字段用 public，跟 {@link io.edap.raft.RaftConfig} 的风格一致。</p>
 */
public class RaftClusterConfig {

    /**
     * 本节点对外可访问的地址（raft 传输层用）。留空则由调用方探测。
     */
    public String selfHost;

    /**
     * 本节点 raft 传输层的监听端口（默认 9092）。
     */
    public int selfPort = 9092;

    /**
     * 本节点 HTTP 管理端口（会合端口，默认 1111）。
     *
     * <p>seeds 里写的就是这个端口 —— 节点之间靠它互相「看见」，
     * raft 传输层的地址是从加入应答里拿的，不必写进 seeds。</p>
     */
    public int managerPort = 1111;

    /**
     * 节点身份与成员表的存放目录。
     */
    public String dataDir = "./data/raft";

    /**
     * 一个 tick 折合多少毫秒。
     */
    public long tickMillis = 100;

    /**
     * 选举超时的 tick 数。
     */
    public int electionTick = 10;

    /**
     * 心跳间隔的 tick 数。
     */
    public int heartbeatTick = 1;

    /**
     * 集群成员数上限（1 主 + N 从）。超过这个数的节点只能当客户端。
     */
    public int capacity = 1;

    /**
     * 候选地址列表。支持三种写法：{@code host:port}、{@code host}、
     * {@code 192.168.0.*:port}（只允许最后一段是 {@code *}）。
     */
    public List<String> seeds = new ArrayList<>();

    /**
     * 加入集群的整体超时（毫秒）。
     */
    public long joinTimeoutMillis = 5000;

    /**
     * 广播/探测单个候选的连接超时（毫秒）。
     */
    public long probeTimeoutMillis = 200;

    /**
     * 广播扇出的并发度。
     */
    public int probeConcurrency = 32;

    /**
     * 候选地址总数上限。超过就启动失败 —— 绝不静默截断。
     */
    public int maxCandidates = 256;

    /**
     * 广播仲裁窗口（毫秒）：等多久收同伴的 CLAIM。
     */
    public long claimWindowMillis = 2000;

    /**
     * 提案超时（毫秒）。超时后提案 future 以异常结束。
     */
    public long proposalTimeoutMillis = 5000;
}
