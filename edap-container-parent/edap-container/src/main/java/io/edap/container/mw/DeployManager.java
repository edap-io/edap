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

package io.edap.container.mw;

import io.edap.Edap;
import io.edap.Server;
import io.edap.ServerGroup;
import io.edap.container.Capability;
import io.edap.container.Container;
import io.edap.container.EdapAppClassLoader;
import io.edap.json.Eson;
import io.edap.log.Logger;
import io.edap.log.LoggerManager;
import io.edap.microservice.annotation.ParamConf;
import io.edap.util.CollectionUtils;

import java.io.*;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.locks.ReentrantLock;

import static io.edap.container.consts.CoreConstant.APP_SERVER_GROUO_KEY;
import static io.edap.launcher.JarLauncher.locateBootJarFile;

public class DeployManager {

    static Logger log = LoggerManager.getLogger(DeployManager.class);

    private static final ReentrantLock DEPLOY_LOCK = new ReentrantLock();

    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private Edap edap;
    private Container container;

    private boolean appStarted;

    public DeployManager() {

    }

    public void setEdap(Edap edap) {
        this.edap = edap;
    }

    /**
     * 注入 Container——能力查询（{@link #hasCapability}）由 Container 统一持有，
     * DeployManager 不再单独解析 NodeType 配置。
     */
    public void setContainer(Container container) {
        this.container = container;
    }

    /**
     * 节点是否具备某协议路由能力。委托给 {@link Container#hasCapability(Capability)}。
     */
    public boolean hasCapability(Capability cap) {
        return container != null && container.hasCapability(cap);
    }

    public BaseResult<List<MicroServiceInfo>> queryAppList() {
        BaseResult<List<MicroServiceInfo>> result = new BaseResult<>();
        if (!appStarted) {
            result.setCode(1);
            result.setMessage("应用还未启动");

            return result;
        }
        List<MicroServiceInfo> list = new ArrayList<>();

        result.setData(list);
        return result;
    }

    public BaseResult<String> deployApp(@ParamConf(name = "name") String name,
                                        @ParamConf(name = "version") String version) {
        BaseResult<String> result = new BaseResult<>();
        File appEar = new File(container.appsDir() + File.separator + name + "-" + version + ".ear");

        if (!appEar.exists()) {
            result.setCode(101);
            result.setMessage("应用的包[" + appEar.getName() + "]不存在");
        } else {
            result = container.deploy(appEar);
        }
        return result;
    }

    public BaseResult<String> online(@ParamConf(name = "name") String name,
                                        @ParamConf(name = "version") String version) {
        BaseResult<String> result = new BaseResult<>();
        File appEar = new File(container.appsDir() + File.separator + name + "-" + version + ".ear");

        if (!appEar.exists()) {
            result.setCode(101);
            result.setMessage("应用的包[" + appEar.getName() + "]不存在");
            return result;
        }
        // name/version 是 EAR 文件名参数（artifactId + "T<buildTime>-<mavenVersion>"），
        // 但 container.switchVersion 需要内部 appId（groupId:artifactId）+ composite version
        // （mavenVersion@buildTime）。这两值在 deploy 时已经写到 .deploy/<role>-<appId>.json：
        //   - appId 在文件名里（<role>-<appId>.json 的 <appId> 部分）
        //   - artifactVersion + buildTime 在 JSON 内容里
        // 用 EAR 文件名作为 key 反查 .deploy 记录，零 EAR 解析开销。
        String[] parsed = lookupByEarName(appEar.getName());
        if (parsed == null) {
            result.setCode(101);
            result.setMessage("未找到 EAR " + appEar.getName() + " 的部署记录，请先 /deploy_app");
            return result;
        }
        result = container.switchVersion(parsed[0], parsed[1]);
        return result;
    }

    /**
     * 按 EAR basename 在 .deploy/ 目录里反查 appId + composite version。
     * 读 <role>-<appId>.json（DeployMeta），匹配 earName == targetBasename。
     * 不开 EAR，不跑 EarScanner —— 一次磁盘 list + 几次小 JSON 读取。
     */
    private String[] lookupByEarName(String targetEarName) {
        File deployDir = new File(container.appsDir(), ".deploy");
        if (!deployDir.isDirectory()) return null;
        File[] files = deployDir.listFiles();
        if (files == null) return null;
        for (File f : files) {
            String fname = f.getName();
            // 只看角色文件：current-*.json / staging-*.json / previous-*.json
            int dash = fname.indexOf('-');
            if (dash < 0) continue;
            String role = fname.substring(0, dash);
            if (!"current".equals(role) && !"staging".equals(role) && !"previous".equals(role)) continue;
            DeployMeta meta = readDeployMeta(f);
            if (meta == null) continue;
            if (!targetEarName.equals(meta.getEarName())) continue;
            String appId = fname.substring(dash + 1, fname.length() - ".json".length());
            String composite = compositeFromMeta(meta);
            return new String[]{appId, composite};
        }
        return null;
    }

    private DeployMeta readDeployMeta(File f) {
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] buf = in.readAllBytes();
            return Eson.parseObject(new String(buf, StandardCharsets.UTF_8), DeployMeta.class);
        } catch (Exception e) {
            log.warn("读 {} 失败", l -> l.arg(f.getName()).threw(e));
            return null;
        }
    }

    private String compositeFromMeta(DeployMeta meta) {
        String version = meta.getArtifactVersion();
        String buildTime = meta.getBuildTime();
        if (version != null && version.endsWith("-SNAPSHOT")
                && buildTime != null && !buildTime.isEmpty()) {
            return version + "@" + buildTime;
        }
        return version;
    }
}
