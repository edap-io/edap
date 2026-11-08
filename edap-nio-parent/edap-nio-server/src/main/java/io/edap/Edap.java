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

package io.edap;

import io.edap.config.EdapConfig;
import io.edap.json.Eson;
import io.edap.json.JsonObject;
import io.edap.log.Logger;
import io.edap.log.LoggerManager;
import io.edap.nio.NioSessionIdleReaper;
import io.edap.nio.SelectorProvider;
import io.edap.nio.util.ConfigUtils;
import io.edap.props.Props;
import io.edap.util.CollectionUtils;
import io.edap.util.timing.Timers;

import java.io.*;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.ReentrantLock;

import static io.edap.log.helpers.Util.printError;

/**
 * Edap容器
 * @author: louis.lu
 * @date : 2019-07-08 11:49
 */
public class Edap {

    private static final Logger log = LoggerManager.getLogger(Edap.class);

    /**
     * 会话空闲回收的配置项。不配就是关闭（不启动回收器）。
     */
    public static final String SESSION_IDLE_TIMEOUT_KEY = "edap.nio.session.idleTimeoutMs";

    /**
     * serverGroups 用并发 map：空闲回收线程会遍历它，
     * 与 {@link #addServerGroup} 的写入并发。
     */
    private Map<String, ServerGroup> serverGroups;

    private static final List<SelectorProvider> SELECTOR_PROVIDERS = new CopyOnWriteArrayList<>();
    private static final List<Stoppable>       STOP_HOOKS         = new CopyOnWriteArrayList<>();

    private          int                  monitIndex = 0;
    private volatile EdapState            state;
    private          Map<String, Integer> monitorIndexs = new HashMap<>();
    private final    ReentrantLock        lifecycleLock = new ReentrantLock();
    private          Props                edapProps;

    /**
     * 会话空闲回收器（未配置空闲超时时为 null）。
     */
    private volatile NioSessionIdleReaper idleReaper;

    public Edap() {
        serverGroups = new ConcurrentHashMap<>();
        init();
        state = EdapState.NEW;
    }

    private void init() {
        ClassLoader providerClassLoader = SelectorProvider.class.getClassLoader();
        ServiceLoader<SelectorProvider> loader;
        loader = ServiceLoader.load(SelectorProvider.class, providerClassLoader);
        Iterator<SelectorProvider> iterator = loader.iterator();
        while (iterator.hasNext()) {
            SelectorProvider provider = safelyInstantiate(iterator);
            if (provider != null && !exits(provider)) {
                SELECTOR_PROVIDERS.add(provider);
            }
        }
        BufferedReader reader = null;
        try {
            URL loc = Edap.class.getProtectionDomain().getCodeSource().getLocation();
            File f = new File(loc.toURI());
            // 从 jar 加载时取 jar 的同级目录；从 classes 目录加载(开发/测试)时取目录本身。
            // 找不到 edap.json5 不是致命错误，此时使用默认配置。
            File dir = f.isFile() ? f.getParentFile() : f;
            File conf = new File(dir, "edap.json5");

            if (conf.exists()) {
                reader = new BufferedReader(new InputStreamReader(new FileInputStream(conf), StandardCharsets.UTF_8));
                StringBuilder json = new StringBuilder();
                String line = reader.readLine();
                while (line != null) {
                    json.append(line).append('\n');
                    line = reader.readLine();
                }
                JsonObject jsonObject = Eson.parseV5JsonObject(json.toString());
                this.edapProps = new Props(jsonObject);
            }
        } catch (Exception e) {
            throw new IllegalStateException("Load edap.json5 failed", e);
        } finally {
            if (reader != null) {
                try {reader.close();} catch (IOException e) {}
            }
        }
    }

    public Props getProps() {
        if (edapProps == null) {
            edapProps = new Props(new HashMap<>());
        }
        // 完整配置由 EdapConfig 加载后构造；当前 stub 阶段先返回 null（容器侧 Environment 构造由 Container.start 接好）
        return edapProps;
    }

    public synchronized int getMonitorIndex(String key) {
        Integer v = monitorIndexs.get(key);
        if (v == null) {
            monitorIndexs.put(key, monitIndex++);
            return monitIndex;
        } else {
            return v;
        }
    }

    public List<SelectorProvider> getSelectorProviders() {
        return Collections.unmodifiableList(SELECTOR_PROVIDERS);
    }

    private static SelectorProvider safelyInstantiate(Iterator<SelectorProvider> iterator) {
        try {
            SelectorProvider provider = iterator.next();
            return provider;
        } catch (ServiceConfigurationError e) {
            printError("A EdapLog service provider failed to instantiate:", e);
        }
        return null;
    }

    private static boolean exits(SelectorProvider provider) {
        if (SELECTOR_PROVIDERS == null || SELECTOR_PROVIDERS.size() == 0) {
            return false;
        }
        for (SelectorProvider p : SELECTOR_PROVIDERS) {
            if (p.getClass().getName().equals(provider.getClass().getName())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 注册 Edap 进程停止时的回调 hook。
     *
     * <p>典型用途：{@code Container.attach(edap)} 时把 {@code container::stop} 注册进来，
     * 保证 SIGTERM / {@code Edap.stop()} 时 Container 先于 ServerGroup 完成清理
     * （Container.stop 只做内存级清理：unbind routes / @PreDestroy / appCL.close，
     * 不动 socket；ServerGroup.stop 关闭监听 socket 必须在 routes 已摘除后做）。</p>
     *
     * <p>Edap 对每个 hook 独立 try/catch：单个 hook 抛异常不影响其他 hook 与 ServerGroup.stop()。</p>
     *
     * <p>调用时机不限，但建议在 {@link #addServerGroup(ServerGroup)} 之后、
     * {@link #run()} 之前完成注册（与 attach 同一处）。</p>
     *
     * @param hook 停止回调，null 静默忽略
     * @return this，便于链式
     */
    public Edap addOnStop(Stoppable hook) {
        if (hook != null) {
            STOP_HOOKS.add(hook);
        }
        return this;
    }

    public Edap addServerGroup(ServerGroup serverGroup) {
        lifecycleLock.lock();
        try {
            serverGroup.setEdap(this);
            serverGroups.put(serverGroup.getName(), serverGroup);
            return this;
        } finally {
            lifecycleLock.unlock();
        }
    }

    public Edap addServerGroups(Map<String, ServerGroup> serverGroups) {
        lifecycleLock.lock();
        try {
            for (ServerGroup sg : serverGroups.values()) {
                sg.setEdap(this);
            }
            this.serverGroups.putAll(serverGroups);
            return this;
        } finally {
            lifecycleLock.unlock();
        }
    }

    public ServerGroup getServerGroup(String name) {
        return serverGroups.get(name);
    }

    public Map<String, ServerGroup> getServerGroups() {
        return this.serverGroups;
    }

    public void addServer(Server server) {
        lifecycleLock.lock();
        try {
            ServerGroup sg = null;
            if (CollectionUtils.isEmpty(serverGroups)) {
                sg = new ServerGroup();
                sg.setEdap(this);
                serverGroups.put("default", sg);
            } else {
                for (Map.Entry<String, ServerGroup> entry : serverGroups.entrySet()) {
                    if (sg == null) {
                        sg = entry.getValue();
                    }
                    if ("default".equals(entry.getKey())) {
                        sg = entry.getValue();
                    }
                }
            }
            if (sg != null) {
                sg.addServer(server);
            }
        } finally {
            lifecycleLock.unlock();
        }
    }

    /**
     * 注册一个 server：Edap 尚未运行时仅入队（由 run() 统一 bind），已运行时立即 bind 并开始监听。
     *
     * <p>与 {@link #addServer(Server)} 的区别：addServer 在 run() 之后调用只会入队不会 bind，
     * 适合启动前一次性注册；本方法在 run() 之后调用会真正把端口监听起来，
     * 适合运行时动态挂载（如 raft 节点在容器启动后才加入集群）。</p>
     */
    public void addServerAndBind(Server server) {
        if (server == null) {
            return;
        }
        lifecycleLock.lock();
        try {
            ServerGroup sg = serverGroups.get("default");
            if (sg == null) {
                sg = new ServerGroup();
                sg.setEdap(this);
                sg.setName("default");
                serverGroups.put("default", sg);
            }
            sg.addServerAndBind(server);
        } finally {
            lifecycleLock.unlock();
        }
    }

    public void run() throws IOException {
        lifecycleLock.lock();
        try {
            if (CollectionUtils.isEmpty(serverGroups)) {
                return;
            }

            Runtime.getRuntime().addShutdownHook(shutdownThread(this));
            serverGroups.forEach((k, v) -> {
                System.out.println("ServerGroup [" + k + "] start");
                v.run();

            });

            startSessionIdleReaper();
            state = EdapState.RUNNING;
        } finally {
            lifecycleLock.unlock();
        }
        //int read = System.in.read();
    }

    /**
     * 按配置启动会话空闲回收器。
     *
     * <p><b>默认关闭</b>：{@code edap.nio.session.idleTimeoutMs} 没配（或配了非正数）
     * 就不启动，行为与没有这个组件时完全一致。配了的话从系统属性 / 环境变量 /
     * edap.json5 配置里取（{@link ConfigUtils} 的取值链）。</p>
     */
    private void startSessionIdleReaper() {
        long idleTimeoutMs = parseSessionIdleTimeoutMs();
        if (idleTimeoutMs <= 0) {
            return;
        }
        try {
            NioSessionIdleReaper reaper = new NioSessionIdleReaper(this, idleTimeoutMs, Timers.get());
            reaper.start();
            this.idleReaper = reaper;
        } catch (Throwable t) {
            log.warn("start nio session idle reaper failed", l -> l.threw(t));
        }
    }

    /**
     * 读会话空闲超时配置。没配返回 -1（表示不启用）。
     *
     * <p>取值顺序：系统属性 / 环境变量（{@link ConfigUtils}）→ edap.json5
     * （{@link #getProps()}）。两处都用同一个 key {@value #SESSION_IDLE_TIMEOUT_KEY}。</p>
     */
    public long parseSessionIdleTimeoutMs() {
        Object value = ConfigUtils.getConfigValue(SESSION_IDLE_TIMEOUT_KEY, getConfig(), null);
        if (value != null) {
            return parseIdleTimeout(value);
        }
        Props props = getProps();
        if (props.containsKey(SESSION_IDLE_TIMEOUT_KEY)) {
            return props.getLong(SESSION_IDLE_TIMEOUT_KEY, -1L);
        }
        return -1L;
    }

    private long parseIdleTimeout(Object value) {
        try {
            long ms = Long.parseLong(String.valueOf(value).trim());
            return ms > 0 ? ms : -1L;
        } catch (NumberFormatException e) {
            log.warn("invalid {} value [{}], idle reaper disabled",
                    l -> l.arg(SESSION_IDLE_TIMEOUT_KEY).arg(value));
            return -1L;
        }
    }

    /**
     * 停掉会话空闲回收器。只撤自己的任务，不动平台时间轮（那会连累别的消费方）。
     */
    private void stopSessionIdleReaper() {
        NioSessionIdleReaper reaper = this.idleReaper;
        if (reaper == null) {
            return;
        }
        this.idleReaper = null;
        try {
            reaper.close();
        } catch (Throwable t) {
            log.warn("stop nio session idle reaper failed", l -> l.threw(t));
        }
    }


    public EdapConfig getConfig() {
        return null;
    }

    private Thread shutdownThread(Edap edap) {
        return new Thread(new ShutdownRunner(edap));
    }

    public void stop() {
        lifecycleLock.lock();
        try {
            if (state == EdapState.STOPPED) {
                return;     // 幂等 no-op
            }
            if (state == EdapState.NEW) {                // 还没 start 过
                state = EdapState.STOPPED;
                return;
            }
            if (state == EdapState.STOPPING) {
                return;    // 已经在停
            }
            state.checkTransitionTo(EdapState.STOPPING);
            state = EdapState.STOPPING;
        } finally {
            lifecycleLock.unlock();
        }
        doStop();   // 释放锁后再做 I/O（s.stop 可能阻塞）
    }

    private void doStop() {
        //System.out.println("Edap stop...");
        log.info("Edap stop...");
        // 0. 先停会话空闲回收：它会关连接，别跟停机流程抢
        stopSessionIdleReaper();
        // 1. 再触发 stop hooks（Container 等）：内存级清理，不动 socket
        for (Stoppable hook : STOP_HOOKS) {
            try {
                hook.stop();
            } catch (Throwable t) {
                log.warn("stop hook {} failed", l -> l.arg(hook.getClass().getName()).threw(t));
            }
        }
        // 2. 再停所有 ServerGroup：关闭监听 socket
        for (Map.Entry<String, ServerGroup> sgEntry : serverGroups.entrySet()) {
            //System.out.println("ServerGroup [" + sgEntry.getKey() + "] stop ...");
            log.info("ServerGroup [{}] stop ...", l -> l.arg(sgEntry.getKey()));
            sgEntry.getValue().stop();
            //System.out.println("ServerGroup [" + sgEntry.getKey() + "] stopped");
            log.info("ServerGroup [{}] stopped", l -> l.arg(sgEntry.getKey()));
        }
        //System.out.println("Edap stopped");
        log.info("Edap stopped");
        lifecycleLock.lock();
        try {
            state = EdapState.STOPPED;
        } finally {
            lifecycleLock.unlock();
        }
    }

    class ShutdownRunner implements Runnable {

        private final Edap edap;

        public ShutdownRunner(Edap edap) {
            this.edap = edap;
        }

        @Override
        public void run() {
            edap.stop();
        }
    }
}
