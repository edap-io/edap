/*
 * Copyright 2023 The edap Project
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

package io.edap.http.server.http2;

import java.util.concurrent.atomic.LongAdder;

/**
 * HTTP/2 出站/入站路径的计数（诊断用）。
 *
 * <p>★ <b>默认关闭</b>：计数在热路径上，而 {@code ENABLED} 是 {@code static final}，
 * JIT 会把 {@code if (ENABLED)} 整个消掉 ⇒ 生产路径<b>零开销</b>。
 * 需要时用 {@code -Dedap.http2.writeStats=true} 打开。</p>
 *
 * <p>这些计数回答的是「一条响应到底写了几次 syscall、有多少次半写（要等 OP_WRITE）」
 * 这类问题，诊断完瓶颈之后仍然有用（回归时看「socket 写/响应」有没有回升到 4 以上）。</p>
 */
public final class Http2WriteStats {

    private Http2WriteStats() {
    }

    /** 是否开启计数 —— {@code static final}，JIT 会把关闭状态下的分支整个消掉 */
    public static final boolean ENABLED = Boolean.getBoolean("edap.http2.writeStats");

    /** 产出并入队的帧数 */
    public static final LongAdder framesEnqueued = new LongAdder();
    /** 入队帧的总字节数（含 9 字节帧头） */
    public static final LongAdder frameBytes = new LongAdder();
    /** 从写缓冲借出的缓冲个数 */
    public static final LongAdder buffersBorrowed = new LongAdder();
    /** 调用 socket 写的次数 */
    public static final LongAdder socketWrites = new LongAdder();
    /** socket 写不完整、需要挂写队列等 OP_WRITE 的次数 */
    public static final LongAdder partialWrites = new LongAdder();
    /** 注册 OP_WRITE 的次数 */
    public static final LongAdder opWriteEnables = new LongAdder();
    /** 入站追加的次数 */
    public static final LongAdder inboundAppends = new LongAdder();
    /** 入站字节数 */
    public static final LongAdder inboundBytes = new LongAdder();
    /** 入站累积缓冲扩容的次数 */
    public static final LongAdder inboundGrowths = new LongAdder();
    /** 响应头（HEADERS 帧）的个数 —— 用来把计数摊到「每条响应」 */
    public static final LongAdder responseHeads = new LongAdder();
    /** 因流控窗口耗尽而挂起 body 的次数（响应被拆成多段发） */
    public static final LongAdder fcStalls = new LongAdder();
    /** 其中被「连接级」窗口挡住的次数 */
    public static final LongAdder connStalls = new LongAdder();
    /** 其中被「流级」窗口挡住的次数 */
    public static final LongAdder streamStalls = new LongAdder();
    /** 响应挂在流控上等额度的总时长（纳秒） */
    public static final LongAdder fcStallNanos = new LongAdder();
    /** 连接级窗口见底的次数 */
    public static final LongAdder connWindowEmpty = new LongAdder();
    /** 连接级窗口见底到下一个 WINDOW_UPDATE 到达之间的总等待时长（纳秒） */
    public static final LongAdder connIdleNanos = new LongAdder();
    /** 收到的连接级 WINDOW_UPDATE（streamId=0）个数 */
    public static final LongAdder connWuCount = new LongAdder();
    /** 收到的连接级 WINDOW_UPDATE 增量总和 */
    public static final LongAdder connWuBytes = new LongAdder();
    /** 收到的流级 WINDOW_UPDATE 个数 */
    public static final LongAdder streamWuCount = new LongAdder();
    /** 收到的流级 WINDOW_UPDATE 增量总和 */
    public static final LongAdder streamWuBytes = new LongAdder();
    /**
     * 当前存活（还在 {@code streams} map 里）的流数 —— 诊断用。
     *
     * <p>用来验「streams map 会不会随请求数无限增长」：每条流结束时必须被摘掉，
     * 否则 {@code flushPendingBodies} 每次 WINDOW_UPDATE 都要遍历全部历史流，
     * 连接会随请求数<b>越来越慢</b>。</p>
     */
    public static final java.util.concurrent.atomic.AtomicInteger liveStreams =
            new java.util.concurrent.atomic.AtomicInteger();
    /** 出站累积缓冲「换缓冲」的次数（换一次 = 把已累积的整块搬走） */
    public static final LongAdder bufferSwaps = new LongAdder();
    /** 换缓冲搬运的总字节数 */
    public static final LongAdder bufferSwapBytes = new LongAdder();
    /** 出站 flush（socket 写）的次数 */
    public static final LongAdder flushes = new LongAdder();
    /** flush 时累积缓冲里已写入的字节数总和 */
    public static final LongAdder flushBytes = new LongAdder();
    /** 借出的缓冲容量总和 */
    public static final LongAdder borrowedCapacitySum = new LongAdder();
    /** 借出的缓冲容量最小值 */
    public static final java.util.concurrent.atomic.AtomicLong borrowedCapacityMin =
            new java.util.concurrent.atomic.AtomicLong(Long.MAX_VALUE);
    /** 借出的缓冲容量最大值 */
    public static final java.util.concurrent.atomic.AtomicLong borrowedCapacityMax =
            new java.util.concurrent.atomic.AtomicLong();
    /** 业务 handler 的执行次数 */
    public static final LongAdder handlerCalls = new LongAdder();
    /** 业务 handler 的总耗时（纳秒）—— 每次 dispatch 的整段耗时 */
    public static final LongAdder handlerNanos = new LongAdder();

    /** 清零 —— 压测前调用。 */
    public static void reset() {
        if (!ENABLED) {
            return;
        }
        framesEnqueued.reset();
        frameBytes.reset();
        buffersBorrowed.reset();
        socketWrites.reset();
        partialWrites.reset();
        opWriteEnables.reset();
        inboundAppends.reset();
        inboundBytes.reset();
        inboundGrowths.reset();
        responseHeads.reset();
        fcStalls.reset();
        connStalls.reset();
        streamStalls.reset();
        fcStallNanos.reset();
        connWindowEmpty.reset();
        connIdleNanos.reset();
        connWuCount.reset();
        connWuBytes.reset();
        streamWuCount.reset();
        streamWuBytes.reset();
        handlerCalls.reset();
        handlerNanos.reset();
        bufferSwaps.reset();
        bufferSwapBytes.reset();
        borrowedCapacitySum.reset();
        borrowedCapacityMin.set(Long.MAX_VALUE);
        borrowedCapacityMax.set(0);
        flushes.reset();
        flushBytes.reset();
    }

    /** 打成一行 key=value，便于脚本解析。 */
    public static String dump() {
        return "frames=" + framesEnqueued.sum()
                + " frameBytes=" + frameBytes.sum()
                + " buffersBorrowed=" + buffersBorrowed.sum()
                + " socketWrites=" + socketWrites.sum()
                + " partialWrites=" + partialWrites.sum()
                + " opWriteEnables=" + opWriteEnables.sum()
                + " inboundAppends=" + inboundAppends.sum()
                + " inboundBytes=" + inboundBytes.sum()
                + " inboundGrowths=" + inboundGrowths.sum()
                + " responseHeads=" + responseHeads.sum()
                + " fcStalls=" + fcStalls.sum()
                + " (conn=" + connStalls.sum() + " stream=" + streamStalls.sum() + ")"
                + " fcStallNanos=" + fcStallNanos.sum()
                + " connWindowEmpty=" + connWindowEmpty.sum()
                + " connIdleNanos=" + connIdleNanos.sum()
                + " connWu=" + connWuCount.sum() + "/" + connWuBytes.sum()
                + " streamWu=" + streamWuCount.sum() + "/" + streamWuBytes.sum()
                + " liveStreams=" + liveStreams.get()
                + " bufferSwaps=" + bufferSwaps.sum()
                + " borrowCapSum=" + borrowedCapacitySum.sum()
                + " borrowCapMin=" + (borrowedCapacityMin.get() == Long.MAX_VALUE ? 0 : borrowedCapacityMin.get())
                + " borrowCapMax=" + borrowedCapacityMax.get()
                + " bufferSwapBytes=" + bufferSwapBytes.sum()
                + " flushes=" + flushes.sum()
                + " flushBytes=" + flushBytes.sum()
                + " handlerCalls=" + handlerCalls.sum()
                + " handlerNanos=" + handlerNanos.sum();
    }
}
