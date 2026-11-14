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

/**
 * 一次测量窗口的结果。
 *
 * <p>延迟单位统一为<b>微秒</b>。</p>
 */
public final class BenchResult {

    /** 框架名（"edap" / "google"）。 */
    public final String framework;

    /** blob 的字节数。 */
    public final int blobSize;

    /** 并发 worker 数（共享一条连接）。 */
    public final int concurrency;

    /** 成功调用次数。 */
    public final long ops;

    /** 失败调用次数。 */
    public final long errors;

    /** 测量窗口的实际时长（毫秒）。 */
    public final double elapsedMillis;

    /** p50 延迟（微秒）。 */
    public final double p50;

    /** p90 延迟（微秒）。 */
    public final double p90;

    /** p99 延迟（微秒）。 */
    public final double p99;

    /** 最大延迟（微秒）。 */
    public final double max;

    /** 平均延迟（微秒）。 */
    public final double mean;

    /** 第一个错误的描述（没有错误时为 null）。 */
    public final String firstError;

    public BenchResult(String framework, int blobSize, int concurrency, long ops, long errors,
                       double elapsedMillis, double p50, double p90, double p99, double max,
                       double mean, String firstError) {
        this.framework = framework;
        this.blobSize = blobSize;
        this.concurrency = concurrency;
        this.ops = ops;
        this.errors = errors;
        this.elapsedMillis = elapsedMillis;
        this.p50 = p50;
        this.p90 = p90;
        this.p99 = p99;
        this.max = max;
        this.mean = mean;
        this.firstError = firstError;
    }

    /**
     * @return 吞吐量（请求/秒）
     */
    public double rps() {
        if (elapsedMillis <= 0) {
            return 0;
        }
        return ops / (elapsedMillis / 1000.0);
    }

    @Override
    public String toString() {
        return String.format("%s blob=%d conc=%d ops=%d err=%d rps=%.1f p50=%.0fus p99=%.0fus",
                framework, blobSize, concurrency, ops, errors, rps(), p50, p99);
    }
}
