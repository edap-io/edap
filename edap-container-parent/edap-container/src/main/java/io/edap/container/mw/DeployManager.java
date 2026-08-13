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
import io.edap.container.scan.EarScanner;
import io.edap.json.Eson;
import io.edap.launcher.NestedJarFile;
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

    private File appsDir;

    private boolean appStarted;

    public DeployManager() {
        File bootJarFile = locateBootJarFile();
        appsDir = new File(bootJarFile.getParent() + File.separator + "apps");
        if (!appsDir.exists()) {
            appsDir.mkdirs();
        }
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

    public BaseResult<String> startApps() {
        BaseResult<String> result = new BaseResult<>();
        if (DEPLOY_LOCK.tryLock()) {
            try {
                log.info("启动容器内应用...");
                File[] appFiles = appsDir.listFiles((dir, name) -> name.endsWith(".ear"));
                List<NestedJarFile> ears = new ArrayList<>();
                StringBuilder success = new StringBuilder();
                StringBuilder error = new StringBuilder();
                if (appFiles == null || appFiles.length == 0) {
                    result.setCode(0);
                    result.setMessage("没有需要部署的应用");

                    return result;
                }

                List<String> appIds = readDeployAppIds();
                if (CollectionUtils.isEmpty(appIds)) {
                    if (appFiles.length == 1 && appFiles[0].getName().endsWith(".ear")) {
                        try {
                            NestedJarFile ear = new NestedJarFile(appFiles[0]);
                            EarScanner scanner = new EarScanner(ear);
                            DeployMetaData dmd = scanner.scanDeployMetaData();
                            dmd.setOrignalFile(appFiles[0]);
                            String appId = dmd.getMavenInfo().getGroupId() + ":" + dmd.getMavenInfo().getArtifactId();
                            addDeployAppId(appId);
                            addDeployMetaFile(appId, "current", appFiles[0], dmd);
                        } catch (IOException e) {
                            error.append(appFiles[0].getName() + "文件不是edap的应用包\n\t").append(e.getMessage()).append("\n");
                        }
                    } else {
                        result.setCode(1);
                        result.setMessage("没有部署任何应用");
                        return result;
                    }
                }

                appIds = readDeployAppIds();
                if (CollectionUtils.isEmpty(appIds)) {
                    result.setCode(1);
                    result.setMessage("没有部署任何应用");
                    return result;
                }

                Map<String, DeployMetaData> dmds = new HashMap<>();
                List<File> earFiles = new ArrayList<>();
                for (String appId : appIds) {
                    try {
                        DeployInfo deployInfo = readDeployInfo(appId);
                        if (deployInfo.getCurrent() == null) {
                            result.setCode(1);
                            result.setMessage(appId + "还没有进行过部署");

                            return result;
                        } else {
                            File ef = new File(appsDir, deployInfo.getCurrent().getEarName());
                            earFiles.add(ef);
                        }
                    } catch (RuntimeException e) {
                        throw new RuntimeException(e);
                    }
                }

                for (File f : earFiles) {
                    try {
                        NestedJarFile ear = new NestedJarFile(f);
                        EarScanner scanner = new EarScanner(ear);
                        DeployMetaData dmd = scanner.scanDeployMetaData();
                        dmd.setOrignalFile(f);
                        String appId = dmd.getMavenInfo().getGroupId() + ":" + dmd.getMavenInfo().getArtifactId();
                        dmds.put(appId, dmd);
                    } catch (IOException e) {
                        error.append(f.getName() + "文件不是edap的应用包\n\t").append(e.getMessage()).append("\n");
                    }
                }

                ClassLoader parent = DeployManager.class.getClassLoader();
                for (Map.Entry<String, DeployMetaData> entry : dmds.entrySet()) {
                    try {
                        EdapAppClassLoader loader = new EdapAppClassLoader(entry.getValue().getOrignalFile(), parent);
                        appBeanInit(entry.getKey(), entry.getValue(), loader);
                        deployAppToContainer(entry.getKey(), entry.getValue());
                    } catch (Exception e) {
                        log.warn("");
                    }
                }
                result.setCode(0);
                result.setMessage("");
                return result;
            } finally {
                DEPLOY_LOCK.unlock();
            }
        } else {
            result.setCode(0);
            result.setMessage("有其他进程正在部署，稍后再试");

            return result;
        }
    }

    private void appBeanInit(String appId, DeployMetaData dmd, EdapAppClassLoader classLoader) {

    }

    /**
     * 按节点能力选择性启动协议 Router（替代旧的 httpEnable/eRPCEnable + NodeType）。
     * 能力集合由 {@link Container#capabilities()} 持有，启动期解析一次。
     *
     * <p>当前阶段（Stage 1）：ServerGroup 占位，等 Stage 4（多协议 Router）落地后
     * 在此按 capabilities() 逐个 bind 对应 Router。</p>
     */
    private void deployAppToContainer(String appId, DeployMetaData dmd) {
        Map<String, ServerGroup> groups = edap.getServerGroups();
        ServerGroup sg = groups.get(APP_SERVER_GROUO_KEY);
        // 如果应用的服务组还没有创建则创建应用的服务组
        if (sg == null) {
            sg = new ServerGroup();
            if (hasCapability(Capability.HTTP)) {
                // bind http router（Stage 4）
            }
            if (hasCapability(Capability.WS)) {
                // bind ws router（Stage 4，同端口按 path 分流）
            }
            if (hasCapability(Capability.ERPC)) {
                // bind erpc router（Stage 4）
            }
            if (hasCapability(Capability.GRPC)) {
                // bind grpc router（Stage 4）
            }
            groups.put(APP_SERVER_GROUO_KEY, sg);
        } else {
            List<Server> servers = sg.getServers();
            for (Server server : servers) {

            }
        }
    }

    private void addDeployMetaFile(String appId, String current, File earFile, DeployMetaData dmd) {
        File deployDir = new File(appsDir, ".deploy");
        if (!deployDir.exists()) {
            deployDir.mkdirs();
        }
        if (!deployDir.exists()) {
            throw new RuntimeException(deployDir.getAbsolutePath() + "目录创建失败");
        }
        File metaFile = new File(deployDir, current + "-" + appId + ".json");
        boolean needCreate = true;
        if (metaFile.exists()) {
            String json = readToString(metaFile);
            if (json == null || json.isEmpty()) {
                needCreate = true;
            } else {
                DeployMeta dm = Eson.parseObject(json, DeployMeta.class);
            }
        } else {
            needCreate = true;
        }
        if (needCreate) {
            if (!metaFile.exists()) {
                try {
                    metaFile.createNewFile();
                } catch (IOException e) {
                    throw new RuntimeException("Cann't create file " + metaFile.getAbsolutePath());
                }
            }
            try (FileOutputStream out = new FileOutputStream(metaFile)) {
                LocalDateTime now = LocalDateTime.now();
                String deployTime = now.format(TIME_FORMATTER);
                DeployMeta dm = new DeployMeta();
                dm.setEarName(earFile.getName());
                dm.setBuildTime(dmd.getBuildInfo().getBuildTime());
                dm.setArtifactVersion(dmd.getMavenInfo().getVersion());
                dm.setDeployer("container");
                dm.setOnliner("container");
                dm.setPreviousEarName("");
                dm.setDeployTime(deployTime);
                dm.setOnlineTime(deployTime);
                out.write(Eson.toJsonString(dm).getBytes(StandardCharsets.UTF_8));
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }

    private void addDeployAppId(String appId) {
        File deployDir = new File(appsDir, ".deploy");
        if (!deployDir.exists()) {
            deployDir.mkdirs();
        }
        if (!deployDir.exists()) {
            throw new RuntimeException(deployDir.getAbsolutePath() + "目录创建失败");
        }
        File appsFile = new File(deployDir, "apps.json");
        List<String> appIds = new ArrayList<>();
        if (!appsFile.exists()) {
            appIds.add(appId);
            try {
                appsFile.createNewFile();
            } catch (IOException e) {
                throw new RuntimeException("Cann't create file " + appsFile.getAbsolutePath());
            }
            try (FileOutputStream out = new FileOutputStream(appsFile)) {
                out.write(Eson.toJsonString(appIds).getBytes(StandardCharsets.UTF_8));
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }

    /**
     * 获取该容器部署的appId的列表
     * @return
     */
    private List<String> readDeployAppIds() {
        File deployDir = new File(appsDir, ".deploy");
        if (!deployDir.exists()) {
            deployDir.mkdirs();
        }
        if (!deployDir.exists()) {
            throw new RuntimeException(deployDir.getAbsolutePath() + "目录创建失败");
        }
        File appsFile = new File(deployDir, "apps.json");
        if (!appsFile.exists()) {
            return Collections.EMPTY_LIST;
        }
        List<Object> jarray = Eson.parseArray(readToString(appsFile));
        if (jarray == null || jarray.isEmpty()) {
            return Collections.EMPTY_LIST;
        }
        List<String> apps = new ArrayList<>();
        for (Object obj : jarray) {
            apps.add(String.valueOf(obj));
        }

        return apps;
    }

    /**
     * 读apps目录中的".deploy"目录，如果有这个目录读取部署的源文件
     * ${appId}-current.json 当前运行的元数据，有运行的ear文件名以及部署时间等
     * ${appId}-previous.json 为上一个部署的元数据
     * ${appId}-staging.json 为预上线的原数据，该部署所有应用的bean已经已经生成，只待切换到生产
     * ${appId}-history.jsonl 为记录的部署历史记录，将每次的部署原数据追加到该文件每行一个部署原数据
     * @return
     */
    private DeployInfo readDeployInfo(String appId) {
        File deployDir = new File(appsDir, ".deploy");
        if (!deployDir.exists()) {
            deployDir.mkdirs();
        }
        if (!deployDir.exists()) {
            throw new RuntimeException(deployDir.getAbsolutePath() + "目录创建失败");
        }
        DeployInfo deployInfo = new DeployInfo();
        deployInfo.setCurrent(readDeployMeta(deployDir, "current-" + appId + ".json"));
        deployInfo.setPrevious(readDeployMeta(deployDir, "previous-" + appId + ".json"));
        deployInfo.setStaging(readDeployMeta(deployDir, "staging-" + appId + ".json"));
        return deployInfo;
    }

    private DeployMeta readDeployMeta(File deployDir, String fileName) {
        File metaFile = new File(deployDir, fileName);
        if (!metaFile.exists()) {
            return null;
        }

        return Eson.parseObject(readToString(metaFile), DeployMeta.class);
    }

    private String readToString(File file) {
        try (InputStream in = new FileInputStream(file)) {
            byte[] buf = new byte[4096];
            int len = in.read(buf);
            String json;
            if (len < buf.length) {
                json = new String(buf, 0, len, StandardCharsets.UTF_8);
            } else {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                do {
                    out.write(buf, 0, len);
                    len = in.read(buf);
                } while (len != -1);
                json = new String(out.toByteArray(), StandardCharsets.UTF_8);
            }
            return json;
        } catch (IOException e) {
            return null;
        }
    }

    public BaseResult<String> deployApp(@ParamConf(name = "name") String name,
                                        @ParamConf(name = "version") String version) {
        BaseResult<String> result = new BaseResult<>();
        //System.out.println("DeployManager classloader: " + this.getClass().getClassLoader());
        File bootJarFile = locateBootJarFile();
        File appsDir = new File(bootJarFile.getParent() + File.separator + "apps");
        if (!appsDir.exists()) {
            result.setCode(100);
            result.setMessage("apps目录不存在");
        } else {
            File appEar = new File(appsDir + File.separator + name + "-" + version + ".ear");

            if (!appEar.exists()) {
                result.setCode(101);
                result.setMessage("应用的包[" + appEar.getName() + "]不存在");
            } else {
                deploy(appEar, result);
            }
        }
        return result;
    }

    private void deploy(File appEar, BaseResult<String> result) {
        String threadName = Thread.currentThread().getName();
        if (DEPLOY_LOCK.tryLock()) {
            System.out.println(threadName + " lock success");
            try {
                NestedJarFile ear = new NestedJarFile(appEar);
                EarScanner scanner = new EarScanner(ear);
                DeployMetaData dmd = scanner.scanDeployMetaData();
            } catch (IOException e) {
                result.setCode(103);
                result.setMessage("ear的包结构错误");
            } finally {
                DEPLOY_LOCK.unlock();
                System.out.println(threadName + " unlock");
            }
        } else {
            result.setCode(104);
            result.setMessage("有其他进程正在部署，稍后再试");
        }
    }
}
