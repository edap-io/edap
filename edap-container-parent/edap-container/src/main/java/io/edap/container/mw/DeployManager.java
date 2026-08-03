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

import io.edap.launcher.NestedJarFile;
import io.edap.microservice.annotation.ParamConf;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static io.edap.launcher.JarLauncher.locateBootJarFile;

public class DeployManager {

    public List<MicroServiceInfo> queryMicroServiceList() {
        List<MicroServiceInfo> list = new ArrayList<>();


        return list;
    }

    public BaseResult<String> deployMicroService(@ParamConf(name = "name") String name,
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
            System.out.println("appEar=" + appEar.getAbsolutePath());
            if (!appEar.exists()) {
                result.setCode(101);
                result.setMessage("应用的包[" + appEar.getName() + "]不存在");
            } else {
                deploy(appEar, result);
            }
        }
        System.out.println(bootJarFile.getAbsolutePath());
        return result;
    }

    private void deploy(File appEar, BaseResult<String> result) {
        try {
            NestedJarFile ear = new NestedJarFile(appEar);
            Set<String> names = ear.entryNames();
            System.out.println("ear files:");
            for (String name : names) {
                System.out.println("\t" + name);
            }
        } catch (IOException e) {
            result.setCode(103);
            result.setMessage("ear的包结构错误");
        }
    }
}
