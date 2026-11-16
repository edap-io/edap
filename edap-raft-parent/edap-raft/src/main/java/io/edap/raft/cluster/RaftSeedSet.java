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

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * seeds 的解析与展开。
 *
 * <p>seeds 里写的是<b>候选节点的 HTTP 管理地址</b>（默认 1111），
 * 不是 raft 传输端口 —— raft 端口从加入应答里拿（对方自报）。</p>
 *
 * <p>支持三种写法：</p>
 * <ul>
 *   <li>{@code 192.168.0.7:1111}：单个地址</li>
 *   <li>{@code raft-1:1111}：主机名</li>
 *   <li>{@code 192.168.0.*:1111}：网段（<b>只允许最后一段是 {@code *}</b>）</li>
 * </ul>
 *
 * <p>★ <b>超限要炸，不要静默截断</b>：候选数超过上限时抛异常并打印实际数字。
 * 截断的后果是「找不到主」，属于极难查的失败。</p>
 */
public final class RaftSeedSet {

    /**
     * 单条通配展开出来的最大地址数（{@code .1} ~ {@code .254}）。
     */
    public static final int WILDCARD_MAX = 254;

    private RaftSeedSet() {
    }

    /**
     * 把逗号分隔的 seeds 字符串拆开。
     *
     * @return 去掉空白项后的 seed 列表（保持原顺序）
     */
    public static List<String> parse(String seeds) {
        List<String> out = new ArrayList<>();
        if (seeds == null) {
            return out;
        }
        for (String s : seeds.split(",")) {
            String t = s.trim();
            if (!t.isEmpty()) {
                out.add(t);
            }
        }
        return out;
    }

    /**
     * 展开 seeds 成候选地址列表。
     *
     * <p><b>★ 顺序：显式地址在前，通配展开的在后</b>。
     * 广播是并发扇出（先到先得），所以排在前面的地址先被探到 ——
     * 「主在显式 seed 上」这个最常见的情况几十毫秒就能找到，
     * 而通配网段（一个 /24 有 254 个地址）排在后面，探完要一两秒。
     * 组内各自按 (host, port) 排序，保证所有节点看到的相对顺序一致。</p>
     *
     * @param seeds         seed 列表（{@code host:port} 或 {@code host}）
     * @param defaultPort   seed 里没写端口时用的端口
     * @param selfHost      本机地址（{@code host} 或 {@code host:port}），用来排除自己；null 表示不排除
     * @param selfPort      本机的管理端口；{@code selfHost} 带了端口时忽略这个参数
     * @param maxCandidates 候选数上限；超了抛 {@link IllegalArgumentException}
     * @return 候选的 {@code host:port} 列表，已排序并去掉重复
     */
    public static List<String> expand(List<String> seeds, int defaultPort,
                                      String selfHost, int selfPort, int maxCandidates) {
        // 分两组收集：显式地址、通配展开的地址。
        Set<String> explicit = new LinkedHashSet<>();
        Set<String> wildcard = new LinkedHashSet<>();
        for (String seed : seeds) {
            if (seed == null || seed.trim().isEmpty()) {
                continue;
            }
            String s = seed.trim();
            String hostPart = s;
            int port = defaultPort;
            int idx = s.lastIndexOf(':');
            if (idx >= 0) {
                hostPart = s.substring(0, idx).trim();
                String portPart = s.substring(idx + 1).trim();
                try {
                    port = Integer.parseInt(portPart);
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException(
                            "raft seeds 里的端口不是数字: " + s + "（应写成 host:port）");
                }
                if (port <= 0 || port > 65535) {
                    throw new IllegalArgumentException("raft seeds 里的端口越界: " + s);
                }
            }
            if (hostPart.isEmpty()) {
                throw new IllegalArgumentException("raft seeds 里 host 为空: " + s);
            }
            if (hostPart.indexOf('*') >= 0) {
                for (String h : expandWildcard(hostPart, s)) {
                    wildcard.add(h + ":" + port);
                }
            } else {
                explicit.add(hostPart + ":" + port);
            }
        }

        // 组内排序：用仲裁器那套比较（先比 host 字符串序，再比 port 数值）——
        // 纯字符串排序会把 192.168.0.100 排在 192.168.0.1 前面。
        List<String> explicitList = new ArrayList<>(explicit);
        List<String> wildcardList = new ArrayList<>(wildcard);
        explicitList.sort(RaftBootstrapArbiter::compare);
        wildcardList.sort(RaftBootstrapArbiter::compare);

        List<String> out = new ArrayList<>(explicitList.size() + wildcardList.size());
        out.addAll(explicitList);
        for (String w : wildcardList) {
            // 去重：通配展开的地址可能与显式地址重合
            if (!explicit.contains(w)) {
                out.add(w);
            }
        }

        // 排除自己：按 host:port 精确匹配。
        // 只按 host 排除是不够的 —— 同一台机器上跑两个容器（不同管理端口）是常见部署，
        // 按 host 排除会把同伴也排掉。
        String selfKey = selfKey(selfHost, selfPort);
        if (selfKey != null) {
            out.remove(selfKey);
        }

        if (maxCandidates > 0 && out.size() > maxCandidates) {
            throw new IllegalArgumentException("raft seeds 展开后有 " + out.size()
                    + " 个候选，超过上限 " + maxCandidates
                    + "（不静默截断 —— 截断会导致找不到主）；请缩小网段或调大 container.raft.maxCandidates");
        }
        return out;
    }

    /**
     * 展开通配 host。只支持四段式、且只有最后一段是 {@code *}。
     *
     * @param host  通配 host（如 {@code 192.168.0.*}）
     * @param seed  原始 seed（报错时用）
     * @return 展开出的 host 列表（升序）
     */
    private static List<String> expandWildcard(String host, String seed) {
        String[] segs = host.split("\\.", -1);
        if (segs.length != 4) {
            throw new IllegalArgumentException("raft seeds 的通配只支持四段式地址（如 192.168.0.*:1111），"
                    + "不支持: " + seed);
        }
        for (int i = 0; i < segs.length; i++) {
            boolean last = (i == segs.length - 1);
            if (segs[i].indexOf('*') >= 0 && !(last && "*".equals(segs[i]))) {
                throw new IllegalArgumentException("raft seeds 的通配只允许出现在最后一段: " + seed);
            }
            if (!last) {
                int v = parseSegment(segs[i], seed);
                if (v < 0 || v > 255) {
                    throw new IllegalArgumentException("raft seeds 的地址段越界: " + seed);
                }
            }
        }
        String prefix = segs[0] + "." + segs[1] + "." + segs[2] + ".";
        List<String> out = new ArrayList<>(WILDCARD_MAX);
        for (int i = 1; i <= WILDCARD_MAX; i++) {
            out.add(prefix + i);
        }
        return out;
    }

    private static int parseSegment(String seg, String seed) {
        if (seg.isEmpty()) {
            throw new IllegalArgumentException("raft seeds 的地址段为空: " + seed);
        }
        for (int i = 0; i < seg.length(); i++) {
            if (seg.charAt(i) < '0' || seg.charAt(i) > '9') {
                throw new IllegalArgumentException("raft seeds 的地址段不是数字: " + seed);
            }
        }
        return Integer.parseInt(seg);
    }

    /**
     * 本机的排除键，形如 {@code host:port}。
     *
     * @param selfHost 配置的 host（可以带 {@code :port}）
     * @param selfPort 配置的管理端口
     * @return 排除键；无法确定时返回 null
     */
    public static String selfKey(String selfHost, int selfPort) {
        if (selfHost == null || selfHost.trim().isEmpty()) {
            return null;
        }
        String h = selfHost.trim();
        int idx = h.lastIndexOf(':');
        if (idx > 0 && h.indexOf(':') == idx) {
            try {
                int p = Integer.parseInt(h.substring(idx + 1).trim());
                return h.substring(0, idx).trim() + ":" + p;
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("container.raft.host 里的端口不是数字: " + selfHost);
            }
        }
        return h + ":" + selfPort;
    }

    /**
     * 探测本机地址。
     *
     * <p>优先用显式配置的 host；没配就在本机的 site-local IPv4 地址里
     * 找一个<b>落在通配网段内</b>的。挑不出来 ⇒ 抛异常并提示显式配置 ——
     * 不瞎猜一个地址。</p>
     *
     * @param configuredHost 显式配置的 host（可为 null）
     * @param seeds          seeds 列表（用来找通配网段）
     * @return 本机 host
     */
    public static String detectSelfHost(String configuredHost, List<String> seeds) {
        if (configuredHost != null && !configuredHost.trim().isEmpty()) {
            String h = configuredHost.trim();
            int idx = h.lastIndexOf(':');
            return (idx > 0 && h.indexOf(':') == idx) ? h.substring(0, idx).trim() : h;
        }
        List<String> local = listLocalIPv4();
        // 先看有没有正好落在某个通配网段里的
        List<String> matched = new ArrayList<>();
        for (String candidate : local) {
            if (inWildcardNetwork(candidate, seeds)) {
                matched.add(candidate);
            }
        }
        if (!matched.isEmpty()) {
            Collections.sort(matched);
            return matched.get(0);
        }
        // 没有通配网段时，本机地址随便挑一个（但必须存在）
        if (!local.isEmpty()) {
            Collections.sort(local);
            return local.get(0);
        }
        throw new IllegalArgumentException(
                "探测不到本机地址：请显式配置 container.raft.host（当前网络接口里没有可用的 IPv4 地址）");
    }

    /**
     * 本机的 site-local IPv4 地址。
     */
    public static List<String> listLocalIPv4() {
        List<String> out = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> nics = NetworkInterface.getNetworkInterfaces();
            while (nics != null && nics.hasMoreElements()) {
                NetworkInterface nic = nics.nextElement();
                if (!nic.isUp() || nic.isLoopback()) {
                    continue;
                }
                Enumeration<InetAddress> addrs = nic.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress addr = addrs.nextElement();
                    if (addr instanceof Inet4Address && addr.isSiteLocalAddress()) {
                        out.add(addr.getHostAddress());
                    }
                }
            }
        } catch (SocketException e) {
            // 枚举失败就当没有 —— 调用方会退回去要求显式配置
            return out;
        }
        return out;
    }

    /**
     * 某个地址是否落在某个通配 seed 的网段内。
     */
    private static boolean inWildcardNetwork(String address, List<String> seeds) {
        if (seeds == null) {
            return false;
        }
        for (String seed : seeds) {
            if (seed == null || seed.indexOf('*') < 0) {
                continue;
            }
            String hostPart = seed.trim();
            int idx = hostPart.lastIndexOf(':');
            if (idx > 0 && hostPart.indexOf(':') == idx) {
                hostPart = hostPart.substring(0, idx);
            }
            String[] segs = hostPart.split("\\.", -1);
            if (segs.length != 4) {
                continue;
            }
            String[] addrSegs = address.split("\\.", -1);
            if (addrSegs.length != 4) {
                continue;
            }
            boolean ok = true;
            for (int i = 0; i < 3; i++) {
                if (!segs[i].equals(addrSegs[i])) {
                    ok = false;
                    break;
                }
            }
            if (ok) {
                return true;
            }
        }
        return false;
    }
}
