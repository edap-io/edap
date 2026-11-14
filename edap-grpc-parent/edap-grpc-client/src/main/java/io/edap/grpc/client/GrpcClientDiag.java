/*
 * Copyright 2026 The edap Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at:
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.edap.grpc.client;

import java.util.concurrent.atomic.LongAdder;

/**
 * 客户端诊断计数。
 *
 * <p>★ <b>默认关闭</b>：计数在热路径上，而 {@code ENABLED} 是 {@code static final}，
 * JIT 会把 {@code if (ENABLED)} 整个消掉 ⇒ 生产路径<b>零开销</b>。
 * 需要时用 {@code -Dedap.grpc.diag=true} 打开。</p>
 */
public final class GrpcClientDiag {

    private GrpcClientDiag() {
    }

    /** 是否开启计数 —— {@code static final}，JIT 会把关闭状态下的分支整个消掉 */
    public static final boolean ENABLED = Boolean.getBoolean("edap.grpc.diag");

    /** 因流控窗口不足而阻塞等待的次数 */
    public static final LongAdder quotaWaits = new LongAdder();
    /** 因流控窗口不足而阻塞的总时长（纳秒） */
    public static final LongAdder quotaWaitNanos = new LongAdder();
    /** 写请求的总时长（纳秒） */
    public static final LongAdder writeNanos = new LongAdder();
    /** 读一帧的总时长（纳秒） */
    public static final LongAdder readNanos = new LongAdder();
    /** 读到的帧数 */
    public static final LongAdder framesRead = new LongAdder();
    /** 读到的字节数 */
    public static final LongAdder bytesRead = new LongAdder();
    /** 一次 socket 写的次数 */
    public static final LongAdder socketWrites = new LongAdder();
    /** 一次 socket 写的总字节数 */
    public static final LongAdder socketWriteBytes = new LongAdder();
    /** 读线程里「分发一帧」的总时长（纳秒）—— 与 readNanos 的差就是阻塞等数据的时长 */
    public static final LongAdder dispatchNanos = new LongAdder();

    public static void reset() {
        if (!ENABLED) {
            return;
        }
        quotaWaits.reset();
        quotaWaitNanos.reset();
        writeNanos.reset();
        readNanos.reset();
        framesRead.reset();
        bytesRead.reset();
        socketWrites.reset();
        socketWriteBytes.reset();
        dispatchNanos.reset();
    }

    public static String dump() {
        return "quotaWaits=" + quotaWaits.sum()
                + " quotaWaitMs=" + (quotaWaitNanos.sum() / 1_000_000)
                + " writeMs=" + (writeNanos.sum() / 1_000_000)
                + " readMs=" + (readNanos.sum() / 1_000_000)
                + " dispatchMs=" + (dispatchNanos.sum() / 1_000_000)
                + " framesRead=" + framesRead.sum()
                + " bytesRead=" + bytesRead.sum()
                + " socketWrites=" + socketWrites.sum()
                + " socketWriteBytes=" + socketWriteBytes.sum();
    }
}
