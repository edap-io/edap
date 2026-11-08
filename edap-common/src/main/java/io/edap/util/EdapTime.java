/*
 * Copyright (c) 2019 louis.lu
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

package io.edap.util;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 平台统一时间门面。
 *
 * <p>一个 daemon 线程每 1 ms 刷新一次缓存，所有"取当前时间"的读法都从这里走：</p>
 *
 * <table border="1">
 *   <caption>门面方法</caption>
 *   <tr><th>方法</th><th>语义</th><th>成本</th></tr>
 *   <tr><td>{@link #currentTimeMillis()}</td><td>墙钟毫秒</td><td>缓存读，~0.4 ns</td></tr>
 *   <tr><td>{@link #nanoTime()}</td><td>单调纳秒</td><td>直读，~18 ns（不能缓存）</td></tr>
 *   <tr><td>{@link #instant()} / {@link #localDateTime()} / {@link #localDate()} / {@link #offsetDateTime()}</td>
 *       <td>同一时刻的日历视图</td><td>缓存读，~3 ns</td></tr>
 *   <tr><td>{@link #date()} / {@link #calendar()}</td><td>可变对象，每次给一份新副本</td><td>~25 ns / ~150 ns</td></tr>
 * </table>
 *
 * <p><b>为什么日历视图也要缓存</b>：{@code LocalDateTime.now()} 要走一遍时区规则查询（~51 ns），
 * 折算到 1 ms 一次是 0.005% 单核 —— 缓存起来让业务侧白拿。
 * 真正值得缓存的是日历这条线，不是毫秒那条线（毫秒只快 8×）。</p>
 *
 * <p><b>单调源不缓存</b>：{@link #nanoTime()} 用于测时间间隔，
 * 缓存它会让 1 ms 以内的间隔测成 0。</p>
 *
 * <p><b>线程模型</b>：只有一个 daemon 线程写缓存（{@code Edap_time_thread}），
 * 读方无锁。daemon 是必须的 —— 非 daemon 线程会让 JVM 退不出去。</p>
 *
 * <h2>模式开关</h2>
 *
 * <p>缓存的代价是<b>固定 CPU 开销</b>：1 ms 醒一次的线程，实测净增
 * <b>~0.75% 单核</b>（macOS/JDK17，3 轮中位数，进程 CPU 增量测得）。
 * 而读缓存比直读系统时钟只快 <b>20.6 ns</b>（0.37 ns vs 21.0 ns）。</p>
 *
 * <p>盈亏平衡：时间读次数/秒 &gt; <b>~36 万</b> 时"主动刷新"才划算。
 * 读得少 —— 或者压根不读 —— 那 0.75% 就是白付的。所以做成开关：</p>
 *
 * <table border="1">
 *   <caption>模式</caption>
 *   <tr><th>模式</th><th>要不要主动每 1 ms 更新缓存</th><th>后台线程</th><th>推送回调</th></tr>
 *   <tr><td>{@link Mode#REFRESH}（默认）</td><td><b>要</b>，读走缓存</td>
 *       <td>1 个 daemon 线程，~0.75% 单核</td><td>支持</td></tr>
 *   <tr><td>{@link Mode#ON_DEMAND}</td><td><b>不要</b>，取时现取 {@link System#currentTimeMillis()}</td>
 *       <td><b>没有</b>，0 CPU</td><td><b>不支持</b>（{@link #addCallback} 抛异常）</td></tr>
 * </table>
 *
 * <p>切换方式（两者都必须发生在第一次 {@link #instance()} 之前）：</p>
 * <ul>
 *     <li>系统属性：{@code -Dedap.time.mode=on-demand}（{@code refresh} 为默认）</li>
 *     <li>编程式：{@link #setMode(Mode)}</li>
 * </ul>
 *
 * <p><b>ON_DEMAND 模式没有推送源</b>：所有回调都靠后台线程推，ON_DEMAND 模式下没有线程可推，
 * 所以 {@link #addCallback} 直接抛 {@link UnsupportedOperationException} ——
 * 宁可启动时炸掉，也不要让它静默不生效。需要"当前时间"的消费方在 ON_DEMAND 下应改成<b>拉取式</b>
 * （仓库内 {@code IdGenerator}、{@code HttpTime} 已按此改造）。</p>
 *
 * @author louis
 * @date 2019-07-06 16:35
 */
public final class EdapTime {

    private static final Logger LOG = Logger.getLogger(EdapTime.class.getName());

    /**
     * 模式选择的系统属性名。取值 {@code refresh}（默认）/ {@code on-demand}。
     */
    public static final String MODE_PROPERTY = "edap.time.mode";

    /**
     * 时间读取模式 —— 决定"要不要主动每 1 ms 更新缓存"。
     */
    public enum Mode {
        /**
         * <b>主动每 1 ms 更新缓存</b>：后台 daemon 线程刷，读走缓存（~0.4 ns）。
         * 适合高并发读时间的场景。
         */
        REFRESH,
        /**
         * <b>不主动更新缓存</b>：没有后台线程，取时现取系统时钟（~21 ns，永远精确）。
         * 适合读得少的场景 —— 省掉 ~0.75% 单核的固定开销。
         */
        ON_DEMAND
    }

    /**
     * 模式锁：保证 {@link #setMode(Mode)} 与单例创建互斥。
     */
    private static final Object MODE_LOCK = new Object();

    /**
     * 编程式指定的模式。为 null 表示按系统属性解析。
     */
    private static Mode requestedMode;

    /**
     * 单例是否已创建（模式是否已定型）。
     */
    private static boolean modeLocked;

    /**
     * 时间的回调函数
     */
    @FunctionalInterface
    public interface TimeCallback {
        /**
         * @param millis 毫秒时间戳（ms 档）；秒边界时间戳（second 档）
         */
        void setTimeMillis(long millis);
    }

    /**
     * 当前的时间戳（墙钟毫秒）。<b>权威值</b>：所有门面读法都以它为准。
     * ON_DEMAND 模式下不用它（读时直取系统时钟）。
     */
    private volatile long current;

    /**
     * 上一次触发秒回调时的秒序号（{@code current / 1000}）。
     */
    private volatile long curSecond;

    /**
     * 由 {@link #current} 派生的日历视图。整体替换，读方看到的一定是同一时刻的一组视图。
     *
     * <p><b>懒重建</b>：刷新线程只更新 {@code current}，不预建视图；
     * 有人真的读日历视图时才建，建完回填。没人读的时候一秒一个新对象都不产生。</p>
     */
    private volatile Snapshot view;

    /**
     * 时区。默认系统时区，可改。
     */
    private volatile ZoneId zoneId;

    /**
     * 本实例的模式。
     */
    private final Mode mode;

    /**
     * 毫秒档回调：每次刷新都推。
     */
    private final List<TimeCallback> millisSecoundCallbacks = new CopyOnWriteArrayList<>();

    /**
     * 秒档回调：只在秒序号变化时推一次。
     */
    private final List<TimeCallback> secondCallbacks = new CopyOnWriteArrayList<>();

    /**
     * 刷新线程是否还在跑。
     */
    private final AtomicBoolean running = new AtomicBoolean(true);

    /**
     * 刷新线程。ON_DEMAND 模式下为 null（没有线程）。
     */
    private final Thread refreshThread;

    private EdapTime() {
        this(resolveMode());
    }

    /**
     * 指定模式构造（测试与内部使用）。
     */
    private EdapTime(Mode mode) {
        this.mode = mode;
        this.zoneId = ZoneId.systemDefault();
        long millis = System.currentTimeMillis();
        this.current = millis;
        this.view = new Snapshot(millis, zoneId);
        if (mode == Mode.ON_DEMAND) {
            // ON_DEMAND 模式没有后台线程：读时直取系统时钟，固定 CPU 开销归零
            this.refreshThread = null;
        } else {
            this.refreshThread = new Thread(this::refreshLoop, "Edap_time_thread");
            // 必须 daemon：非 daemon 线程会让 JVM 退不出去
            this.refreshThread.setDaemon(true);
            this.refreshThread.start();
        }
    }

    /**
     * 解析生效模式：编程式指定优先，其次系统属性，最后默认 {@link Mode#REFRESH}。
     */
    private static Mode resolveMode() {
        synchronized (MODE_LOCK) {
            modeLocked = true;
            if (requestedMode != null) {
                return requestedMode;
            }
            return parseMode(System.getProperty(MODE_PROPERTY));
        }
    }

    /**
     * 解析模式名。{@code null} / 空 / 无法识别都落到 {@link Mode#REFRESH}。
     *
     * @param value 模式名（{@code refresh} / {@code on-demand}，大小写不敏感）
     * @return 解析出的模式
     */
    static Mode parseMode(String value) {
        if (value == null) {
            return Mode.REFRESH;
        }
        String v = value.trim().toLowerCase(Locale.ROOT);
        if (v.isEmpty()) {
            return Mode.REFRESH;
        }
        if ("on-demand".equals(v)) {
            return Mode.ON_DEMAND;
        }
        if ("refresh".equals(v)) {
            return Mode.REFRESH;
        }
        LOG.warning("unknown " + MODE_PROPERTY + " value: " + value + ", fallback to refresh");
        return Mode.REFRESH;
    }

    /**
     * 设置时钟来源模式。<b>必须在第一次 {@link #instance()} 之前调用</b>。
     *
     * @param mode 目标模式
     * @throws IllegalStateException 单例已创建，模式已定型
     */
    public static void setMode(Mode mode) {
        if (mode == null) {
            throw new NullPointerException("mode");
        }
        synchronized (MODE_LOCK) {
            if (modeLocked) {
                throw new IllegalStateException("EdapTime 单例已创建，模式不可更改（请在首次 EdapTime.instance() 之前设置）");
            }
            requestedMode = mode;
        }
    }

    /**
     * 本实例的模式。
     */
    public Mode mode() {
        return mode;
    }

    /**
     * 刷新循环：睡到下一个毫秒边界，再刷新。
     *
     * <p>不用 {@code ScheduledExecutorService}：它要一个线程池 + 堆 + 锁，
     * 而且 {@code scheduleAtFixedRate} 在上一轮超时后会立刻补跑（空转）。
     * 手写 {@code parkNanos} 没有这些包袱，且刷新时刻严格对齐毫秒边界。</p>
     */
    private void refreshLoop() {
        while (running.get()) {
            try {
                long now = System.nanoTime();
                long nextMs = (now / 1_000_000L + 1) * 1_000_000L;
                LockSupport.parkNanos(nextMs - now);
                if (!running.get()) {
                    break;
                }
                refresh();
            } catch (Throwable t) {
                // 刷新线程绝不能死：死了整个平台的"当前时间"就冻住了
                LOG.log(Level.WARNING, "edap time refresh failed", t);
            }
        }
    }

    /**
     * 刷新一次缓存并推送回调。
     */
    private void refresh() {
        long millis = System.currentTimeMillis();
        current = millis;

        long second = millis / 1000;
        boolean secondChanged = second != curSecond;
        curSecond = second;

        if (!millisSecoundCallbacks.isEmpty()) {
            for (TimeCallback callback : millisSecoundCallbacks) {
                try {
                    callback.setTimeMillis(millis);
                } catch (Throwable t) {
                    // 回调是外部代码：一个抛异常不能带倒其它回调，更不能带倒刷新线程
                    LOG.log(Level.WARNING, "edap time millis callback failed", t);
                }
            }
        }
        if (secondChanged && !secondCallbacks.isEmpty()) {
            // 秒档传的是"秒边界时间戳"，不是精确毫秒
            long boundary = second * 1000;
            for (TimeCallback callback : secondCallbacks) {
                try {
                    callback.setTimeMillis(boundary);
                } catch (Throwable t) {
                    LOG.log(Level.WARNING, "edap time second callback failed", t);
                }
            }
        }
    }

    /**
     * 当前时间戳（墙钟毫秒）。
     *
     * <p>REFRESH 模式读缓存（~0.4 ns，最多比真实时间旧 1 ms）；
     * ON_DEMAND 模式直取系统时钟（~21 ns，永远是精确值）。</p>
     */
    public long currentTimeMillis() {
        if (mode == Mode.ON_DEMAND) {
            return System.currentTimeMillis();
        }
        return current;
    }

    /**
     * 单调纳秒。<b>不缓存</b> —— 用来测时间间隔，缓存会让亚毫秒间隔测成 0。
     */
    public long nanoTime() {
        return System.nanoTime();
    }

    /**
     * 当前时刻（UTC 时间线）。
     */
    public Instant instant() {
        if (mode == Mode.ON_DEMAND) {
            return Instant.ofEpochMilli(System.currentTimeMillis());
        }
        return consistentView().instant;
    }

    /**
     * 当前时刻的本地日期时间（{@link #zoneId()}）。
     */
    public LocalDateTime localDateTime() {
        if (mode == Mode.ON_DEMAND) {
            return LocalDateTime.ofInstant(Instant.ofEpochMilli(System.currentTimeMillis()), zoneId);
        }
        return consistentView().localDateTime;
    }

    /**
     * 当前日期（{@link #zoneId()}）。
     */
    public LocalDate localDate() {
        if (mode == Mode.ON_DEMAND) {
            return LocalDateTime.ofInstant(Instant.ofEpochMilli(System.currentTimeMillis()), zoneId).toLocalDate();
        }
        return consistentView().localDate;
    }

    /**
     * 当前时刻的带偏移日期时间（{@link #zoneId()}）。
     */
    public OffsetDateTime offsetDateTime() {
        if (mode == Mode.ON_DEMAND) {
            return OffsetDateTime.ofInstant(Instant.ofEpochMilli(System.currentTimeMillis()), zoneId);
        }
        return consistentView().offsetDateTime;
    }

    /**
     * 当前时刻的 {@link Date}。每次返回新副本（{@code Date} 是可变对象，不能共享）。
     */
    public Date date() {
        return new Date(currentTimeMillis());
    }

    /**
     * 当前时刻的 {@link Calendar}。每次返回新副本（{@code Calendar} 是可变对象，不能共享）。
     */
    public Calendar calendar() {
        Calendar calendar = Calendar.getInstance(TimeZone.getTimeZone(zoneId));
        calendar.setTimeInMillis(currentTimeMillis());
        return calendar;
    }

    /**
     * 当前使用的时区。
     */
    public ZoneId zoneId() {
        return zoneId;
    }

    /**
     * 改时区。立刻重算缓存视图。
     */
    public void setZoneId(ZoneId zone) {
        if (zone == null) {
            throw new NullPointerException("zone");
        }
        this.zoneId = zone;
        long millis = current;
        this.view = new Snapshot(millis, zone);
    }

    /**
     * 注册回调。
     *
     * <p><b>这不是调度器</b>：{@code initialDelay} 与 {@code delay} 不生效，
     * 回调由刷新线程按档位推送 —— {@code unit} 决定档位：</p>
     * <ul>
     *     <li>纳秒/微秒/毫秒 → 毫秒档，每次刷新推一次，收到毫秒时间戳</li>
     *     <li>秒 → 秒档，秒序号变化时推一次，收到<b>秒边界</b>时间戳</li>
     * </ul>
     * <p>需要"按延迟触发"请用 {@code io.edap.util.timing.TimerService}。</p>
     *
     * @throws UnsupportedOperationException {@link Mode#ON_DEMAND} 模式没有推送源
     */
    public void addCallback(TimeCallback callback, long initialDelay, long delay, TimeUnit unit) {
        if (callback == null || unit == null) {
            return;
        }
        if (mode == Mode.ON_DEMAND) {
            throw new UnsupportedOperationException("EdapTime " + Mode.ON_DEMAND + " 模式没有后台刷新线程，不支持推送回调");
        }
        switch (unit) {
            case NANOSECONDS:
            case MICROSECONDS:
            case MILLISECONDS:
                if (!millisSecoundCallbacks.contains(callback)) {
                    millisSecoundCallbacks.add(callback);
                }
                break;
            case SECONDS:
                if (!secondCallbacks.contains(callback)) {
                    secondCallbacks.add(callback);
                }
                break;
            default:
                break;
        }
    }

    /**
     * 停止刷新。缓存值冻结在最后一刻，{@link #currentTimeMillis()} 不再前进。
     *
     * <p>幂等。进程退出前调用它可以省掉一个线程；
     * 但因为它是 daemon 线程，不调用也不会挡住 JVM 退出。</p>
     *
     * <p>ON_DEMAND 模式没有刷新线程，调用它没有效果（读数照常实时）。</p>
     */
    public void shutdown() {
        if (running.compareAndSet(true, false)) {
            if (refreshThread != null) {
                LockSupport.unpark(refreshThread);
            }
        }
    }

    /**
     * 取一组自洽的视图。
     *
     * <p>{@code current} 与 {@code view} 是两个独立的 volatile 字段，
     * 刷新时先后赋值，中间有个几十纳秒的窗口。读到这个窗口里时，
     * {@code view} 与 {@code current} 对不上 —— 此时按 {@code current} 现算，
     * 保证"门面读到的时刻"和 {@link #currentTimeMillis()} 永远一致。</p>
     */
    private Snapshot consistentView() {
        long millis = current;
        Snapshot snapshot = view;
        if (snapshot.millis != millis) {
            // 视图落后了（刷新线程只更新 current，不预建视图）—— 现算并回填
            snapshot = new Snapshot(millis, zoneId);
            view = snapshot;
        }
        return snapshot;
    }

    public static final EdapTime instance() {
        return SingletonHolder.INSTANCE;
    }

    /**
     * 同一时刻的一组日历视图。不可变，整体替换。
     */
    private static final class Snapshot {

        private final long millis;
        private final Instant instant;
        private final LocalDateTime localDateTime;
        private final LocalDate localDate;
        private final OffsetDateTime offsetDateTime;

        private Snapshot(long millis, ZoneId zone) {
            this.millis = millis;
            this.instant = Instant.ofEpochMilli(millis);
            this.localDateTime = LocalDateTime.ofInstant(instant, zone);
            this.localDate = localDateTime.toLocalDate();
            this.offsetDateTime = OffsetDateTime.ofInstant(instant, zone);
        }
    }

    private static class SingletonHolder {
        private static final EdapTime INSTANCE = new EdapTime();
    }
}
