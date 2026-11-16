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

import java.util.Collection;

/**
 * 并发启动时的确定性仲裁：<b>地址最小者自举成主</b>。
 *
 * <p>为什么需要它：「第一个加入的自举成主」在<b>多个容器同时启动</b>时会分裂 ——
 * 4 个都探不到「已有集群」⇒ 4 个都自举 ⇒ 两个主。启动广播让彼此看见，
 * 但看见之后还得有人拍板。</p>
 *
 * <p>仲裁规则 = <b>(host, port) 字典序最小者胜</b>：</p>
 * <ul>
 *   <li>全局一致：所有节点用同一条规则算，结论必然相同；</li>
 *   <li>不依赖时钟同步；</li>
 *   <li>不需要中心仲裁者。</li>
 * </ul>
 *
 * <p>★ <b>窗口必须等满再自举</b>：否则「漏看更小的同伴」会造出第二个主。
 * 等满之后，本节点看到的最小者就是自己 ⇒ 不会分裂。</p>
 *
 * <p>★ <b>部分可见也不分裂</b>：A 能看到 B、B 看不到 A 时，
 * A 看到 B（地址更小）⇒ A 等 B；B 看不到 A ⇒ B 自举成主 ⇒ 最终只有一个主
 * （B 成主后 A 会收到 LEADER_ACK 再加入）。</p>
 */
public final class RaftBootstrapArbiter {

    private RaftBootstrapArbiter() {
    }

    /**
     * 本节点该不该自举成主。
     *
     * @param selfHost 本节点 host
     * @param selfPort 本节点管理端口
     * @param peers   窗口内发现的同伴（{@code host:port} 形式的管理地址）
     * @return true 表示本节点自举成主
     */
    public static boolean shouldBootstrap(String selfHost, int selfPort, Collection<String> peers) {
        if (peers == null || peers.isEmpty()) {
            // 没有同伴 ⇒ 确实只有自己 ⇒ 自举
            return true;
        }
        String self = selfHost + ":" + selfPort;
        for (String peer : peers) {
            if (peer == null || peer.isEmpty()) {
                continue;
            }
            if (compare(peer, self) < 0) {
                // 有同伴比我小 ⇒ 我不当主，等他
                return false;
            }
        }
        return true;
    }

    /**
     * 比较两个 {@code host:port} 地址：先比 host（字符串序），再比 port（数值）。
     *
     * @return a &lt; b 返回负数，a &gt; b 返回正数，相等返回 0
     */
    public static int compare(String a, String b) {
        String hostA = hostOf(a);
        String hostB = hostOf(b);
        int c = hostA.compareTo(hostB);
        if (c != 0) {
            return c;
        }
        return Integer.compare(portOf(a), portOf(b));
    }

    /**
     * 地址里的 host 部分。
     */
    public static String hostOf(String address) {
        if (address == null) {
            return "";
        }
        int idx = address.lastIndexOf(':');
        return idx > 0 ? address.substring(0, idx) : address;
    }

    /**
     * 地址里的端口部分。解析不出来时返回 0。
     */
    public static int portOf(String address) {
        if (address == null) {
            return 0;
        }
        int idx = address.lastIndexOf(':');
        if (idx <= 0 || idx == address.length() - 1) {
            return 0;
        }
        try {
            return Integer.parseInt(address.substring(idx + 1));
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
