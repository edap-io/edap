/*
 * Copyright 2023 The edap Project
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */

package io.edap.nio;

import io.edap.Edap;
import io.edap.NioServerSession;
import io.edap.Server;
import io.edap.ServerGroup;
import io.edap.log.Logger;
import io.edap.log.LoggerManager;
import io.edap.util.EdapTime;
import io.edap.util.timing.TimerHandle;
import io.edap.util.timing.TimerService;

import java.nio.channels.SelectionKey;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * NIO 会话空闲回收 —— 把长时间没有读也没有写的连接关掉。
 *
 * <p><b>默认关闭</b>：不配置 {@code edap.nio.session.idleTimeoutMs} 就完全不起作用，
 * 行为与没有这个组件时一模一样。配置了才启动。</p>
 *
 * <p><b>空闲判定</b>：{@code now - max(lastReadTime, lastWriteTime) > idleTimeoutMs}。
 * 两个时间戳都是墙钟毫秒（与写入侧同源），所以 NTP 校时只影响判定精度，
 * 不影响判定方向 —— 校时往回跳只会让连接晚一点被回收，不会误杀刚活跃的连接。</p>
 *
 * <p><b>扫描周期</b>：从 idleTimeout 推导（{@code clamp(idleTimeout/4, 1s, 60s)}），
 * 不单独配置。实际回收时刻落在 {@code [T, T+扫描周期]} 区间内。</p>
 *
 * <p><b>定时器</b>：走平台共享的时间轮（{@link io.edap.util.timing.Timers}），
 * 不自己开 {@code ScheduledExecutorService}。关停时只撤自己的任务，
 * 不会把平台时间轮一起关掉（那会连累别的消费方）。</p>
 */
public class NioSessionIdleReaper implements AutoCloseable {

    private static final Logger LOG = LoggerManager.getLogger(NioSessionIdleReaper.class);

    /**
     * 扫描周期下限 / 上限（毫秒）。
     */
    private static final long MIN_SCAN_INTERVAL_MS = 1_000L;
    private static final long MAX_SCAN_INTERVAL_MS = 60_000L;

    private final Edap             edap;
    private final long             idleTimeoutMs;
    private final long             scanIntervalMs;
    private final TimerService     timerService;
    private final AtomicBoolean    closed = new AtomicBoolean();
    private final EdapTime         edapTime = EdapTime.instance();

    private volatile TimerHandle handle;

    public NioSessionIdleReaper(Edap edap, long idleTimeoutMs, TimerService timerService) {
        if (edap == null) {
            throw new NullPointerException("edap");
        }
        if (timerService == null) {
            throw new NullPointerException("timerService");
        }
        if (idleTimeoutMs <= 0) {
            throw new IllegalArgumentException("idleTimeoutMs must be positive");
        }
        this.edap = edap;
        this.idleTimeoutMs = idleTimeoutMs;
        this.scanIntervalMs = deriveScanInterval(idleTimeoutMs);
        this.timerService = timerService;
    }

    /**
     * 扫描周期 = clamp(idleTimeout/4, 1s, 60s)。
     *
     * <p>取 1/4 是为了让「判定延迟」不超过超时阈值的 1/4；上限 60s 是为了
     * 超时配得很大时也不至于几十秒才扫一次（长连接场景没必要那么频繁）。</p>
     */
    static long deriveScanInterval(long idleTimeoutMs) {
        long interval = idleTimeoutMs / 4;
        if (interval < MIN_SCAN_INTERVAL_MS) {
            interval = MIN_SCAN_INTERVAL_MS;
        }
        if (interval > MAX_SCAN_INTERVAL_MS) {
            interval = MAX_SCAN_INTERVAL_MS;
        }
        return interval;
    }

    /**
     * 启动回收。重复调用是 no-op。
     */
    public void start() {
        if (closed.get()) {
            throw new IllegalStateException("reaper is closed");
        }
        if (handle != null) {
            return;
        }
        this.handle = timerService.scheduleAtFixedRate(this::scanSafely,
                scanIntervalMs, scanIntervalMs, TimeUnit.MILLISECONDS);
        LOG.info("nio session idle reaper started, idleTimeout={}ms scanInterval={}ms",
                l -> l.arg(idleTimeoutMs).arg(scanIntervalMs));
    }

    /**
     * 扫一轮，把空闲超时的连接关掉。返回本次关闭的连接数。
     *
     * <p>包级可见 —— 测试直接调它，不依赖定时器时序。</p>
     */
    public int scan() {
        long now = edapTime.currentTimeMillis();
        AtomicInteger closedCount = new AtomicInteger();
        for (ServerGroup group : edap.getServerGroups().values()) {
            for (Server server : group.getServers()) {
                Map<Server.Addr, IoSelectorManager> managers = server.getIoSelectorManagerMap();
                if (managers == null || managers.isEmpty()) {
                    continue;
                }
                for (IoSelectorManager manager : managers.values()) {
                    closedCount.addAndGet(scanServer(server, manager, now));
                }
            }
        }
        int total = closedCount.get();
        if (total > 0) {
            LOG.info("nio session idle reaper closed {} idle session(s)", l -> l.arg(total));
        }
        return total;
    }

    private int scanServer(Server server, IoSelectorManager manager, long now) {
        AtomicInteger closedCount = new AtomicInteger();
        manager.forEachSession((key, session) -> {
            if (closed.get()) {
                return;
            }
            if (!key.isValid()) {
                // 已被别的路径取消 —— 不用管
                return;
            }
            long last = Math.max(session.getLastReadTime(), session.getLastWriteTime());
            if (last <= 0) {
                // 从没活跃过（刚 accept 还没读到数据）—— 不按空闲处理
                return;
            }
            if (now - last <= idleTimeoutMs) {
                return;
            }
            NioSessionCloser.close(server, key, session);
            closedCount.incrementAndGet();
        });
        return closedCount.get();
    }

    /**
     * 定时器回调 —— 扫描本身抛异常不能把定时任务打断（否则整个回收就停了）。
     */
    private void scanSafely() {
        try {
            scan();
        } catch (Throwable t) {
            LOG.warn("nio session idle reaper scan failed", l -> l.threw(t));
        }
    }

    public long getIdleTimeoutMs() {
        return idleTimeoutMs;
    }

    public long getScanIntervalMs() {
        return scanIntervalMs;
    }

    /**
     * 停掉回收。幂等。
     */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        TimerHandle h = handle;
        if (h != null) {
            h.cancel();
        }
        LOG.info("nio session idle reaper stopped");
    }
}
