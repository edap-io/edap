/*
 * Copyright 2021 The edap Project
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

package io.edap.plugin.mvn;

import io.edap.protobuf.codegen.CodeGenertor;

import org.apache.maven.model.Resource;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

@Mojo(name = "generate",
        defaultPhase = LifecyclePhase.GENERATE_SOURCES,
        threadSafe = true)
public class ProtocMojo extends AbstractMojo {

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        File baseDir = project.getBasedir();
        List<String> sources = project.getCompileSourceRoots();
        String srcDir;
        if (sources == null || sources.size() > 1) {
            getLog().error("请指定java源代码的目录");
            return;
        }
        srcDir = sources.get(0);
        List<Resource> resources = project.getResources();
        List<String> protoPaths = new ArrayList<>();
        for (Resource resource : resources) {
            File f = new File(resource.getDirectory() + File.separator + "proto");
            if (f.exists()) {
                protoPaths.add(resource.getDirectory() + File.separator + "proto");
                getLog().info("proto目录" + f.getAbsolutePath());
            } else {
                getLog().warn(f.getAbsolutePath() + " 目录不存在");
            }
        }
        for (String protoPath : protoPaths) {
            try {
                CodeGenertor.generate(protoPath, srcDir);
            } catch (IOException e) {
                getLog().error(e);
            }
        }
    }
}
