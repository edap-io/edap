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

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 提案的待完成表：requestId → future。
 *
 * <p>apply 时按 requestId 拆信封、匹配 future、把裸 payload 交给用户的
 * ApplyHandler。<b>信封不进业务状态机</b>。</p>
 *
 * <p>★ <b>幂等</b>：已应用过的 requestId 会被记住（LRU 缓存），
 * 重试带来的重复 apply 会被丢掉 ⇒ 业务状态机只应用一次。</p>
 *
 * <p><b>本地提案不走这里</b>：本地提案用 raft 内核自带的
 * {@code proposeAsync}（提交即完成，语义更强）。本表只服务
 * 「客户端经 HTTP 提案、需要按 requestId 回执」这条路径。</p>
 */
public class RaftProposalTracker {

    /** 已应用 requestId 的缓存上限。超出后按 LRU 淘汰。 */
    private static final int APPLIED_CACHE_SIZE = 10000;

    /** 在途提案 */
    private final Map<Long, CompletableFuture<byte[]>> pending = new ConcurrentHashMap<>();

    /**
     * 已应用过的 requestId（LRU）—— <b>去重的唯一依据</b>。
     *
     * <p>只有真正被业务状态机应用过的 requestId 才会进来；
     * 「只是转发到主、那边说是重复」<b>不能</b>进这张表
     * （否则本节点会误以为载荷已经应用过 ⇒ 后续重试不再转发）。</p>
     */
    private final LinkedHashMap<Long, Boolean> applied =
            new LinkedHashMap<>(256, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<Long, Boolean> eldest) {
                    return size() > APPLIED_CACHE_SIZE;
                }
            };

    /**
     * 最近一次回执是「重复」的 requestId（LRU）。
     *
     * <p>与 {@link #applied} 分开：这张表只回答「上一次关于这个 requestId 的回执
     * 是不是重复应答」，不参与去重判断。</p>
     */
    private final LinkedHashMap<Long, Boolean> duplicateReplies =
            new LinkedHashMap<>(256, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<Long, Boolean> eldest) {
                    return size() > APPLIED_CACHE_SIZE;
                }
            };

    /** applied 与 pending 的写锁。两者必须一起看，不能各改各的。 */
    private final Object lock = new Object();

    /**
     * 登记一个在途提案。<b>必须先登记再提交</b> ——
     * 否则提交可能比登记先发生，future 就永远等不到了。
     *
     * @param requestId 请求 id
     * @return 登记结果：future + 「是不是重试」
     */
    public Registration register(long requestId) {
        CompletableFuture<byte[]> future = new CompletableFuture<>();
        synchronized (lock) {
            if (applied.containsKey(requestId)) {
                // 已经应用过：这是重试。立即回执，不重复提案。
                // ★ 记一笔「本次回执是重复」—— 调用方要能区分
                //   「这次是新应用」与「早先就应用过了」。
                duplicateReplies.put(requestId, Boolean.TRUE);
                future.complete(new byte[0]);
                return new Registration(future, true);
            }
            CompletableFuture<byte[]> exist = pending.putIfAbsent(requestId, future);
            if (exist != null) {
                // 同一个 requestId 已经在途：复用同一个 future，
                // 免得前一个 future 永远等不到（被覆盖了）。
                return new Registration(exist, false);
            }
        }
        return new Registration(future, false);
    }

    /**
     * 提案被 apply 时调用。
     *
     * @param requestId 信封里的 requestId
     * @param payload   裸 payload
     * @return true 表示这是第一次应用（调用方应当把它交给业务）；
     *         false 表示重复的 requestId，直接丢弃
     */
    public boolean complete(long requestId, byte[] payload) {
        if (requestId == 0) {
            // 没有 requestId 的条目不是本层的提案（不该出现），当作普通条目放行
            return true;
        }
        CompletableFuture<byte[]> f;
        synchronized (lock) {
            if (applied.containsKey(requestId)) {
                // 已经应用过 ⇒ 重复（重试），丢掉
                return false;
            }
            applied.put(requestId, Boolean.TRUE);
            duplicateReplies.remove(requestId);
            f = pending.remove(requestId);
        }
        if (f != null) {
            f.complete(payload);
        }
        return true;
    }

    /**
     * 提案失败（节点停了、提案被丢弃等）。
     */
    public void fail(long requestId, Throwable t) {
        CompletableFuture<byte[]> f = pending.remove(requestId);
        if (f != null) {
            f.completeExceptionally(t);
        }
    }

    /**
     * 所有在途提案一起失败（节点停止时）。
     */
    public void failAll(Throwable t) {
        for (Long id : pending.keySet().toArray(new Long[0])) {
            fail(id, t);
        }
    }

    /**
     * 在途提案数。
     */
    public int pendingCount() {
        return pending.size();
    }

    /**
     * 是否已经应用过这个 requestId。
     */
    public boolean isApplied(long requestId) {
        synchronized (lock) {
            return applied.containsKey(requestId);
        }
    }

    /**
     * 记一笔「本次回执是重复应答」。
     *
     * <p><b>只做客户端的节点用</b>：它把提案转发给主，主回「已经应用过了」，
     * 本节点据此如实回给自己的客户端。<b>不写 {@link #applied}</b> ——
     * 本节点自己没应用过这张表就不能有它的条目，否则下一次重试会被本地
     * 去重挡掉、不再转发给主。</p>
     *
     * @param requestId 请求 id
     */
    public void markDuplicateReply(long requestId) {
        synchronized (lock) {
            duplicateReplies.put(requestId, Boolean.TRUE);
        }
    }

    /**
     * 最近一次关于这个 requestId 的回执是不是「重复应答」。
     */
    public boolean isDuplicateReply(long requestId) {
        synchronized (lock) {
            return Boolean.TRUE.equals(duplicateReplies.get(requestId));
        }
    }

    /**
     * 登记结果。
     */
    public static final class Registration {

        private final CompletableFuture<byte[]> future;

        /**
         * true 表示这个 requestId <b>已经应用过了</b> —— 调用方<b>不要</b>再提案，
         * 直接把 future 的结果回给客户端就行。
         */
        private final boolean duplicate;

        Registration(CompletableFuture<byte[]> future, boolean duplicate) {
            this.future = future;
            this.duplicate = duplicate;
        }

        public CompletableFuture<byte[]> getFuture() {
            return future;
        }

        public boolean isDuplicate() {
            return duplicate;
        }
    }
}
