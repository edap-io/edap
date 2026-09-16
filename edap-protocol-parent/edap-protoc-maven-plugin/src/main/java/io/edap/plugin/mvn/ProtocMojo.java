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

import io.edap.protobuf.wire.Proto;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.model.Resource;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.CollectionUtils;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

@Mojo(name = "generate",
        defaultPhase = LifecyclePhase.GENERATE_SOURCES,
        threadSafe = true,
        requiresDependencyResolution = ResolutionScope.COMPILE)
public class ProtocMojo extends AbstractMojo {

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    @Parameter(property = "generate.createImpl", defaultValue = "false")
    private boolean createImpl;

    @Parameter(property = "generate.implModulePath", defaultValue = "")
    private String implModulePath;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        File baseDir = project.getBasedir();
        List<String> sources = project.getCompileSourceRoots();
        String srcDir;
        if (sources == null || sources.size() > 1) {
            throw new MojoFailureException("请指定java源代码的目录");
        }
        if (createImpl) {
            if (implModulePath == null || implModulePath.trim().length() == 0) {
                getLog().error("请配置接口实现模块的路径");
                throw new MojoFailureException("请配置接口实现模块的路径");
            }
        }
        File implBaseDir = new File(baseDir.getAbsolutePath() + File.separator + implModulePath);
        File implSrcDir = new File(implBaseDir + File.separator + "src/main/java/");
        if (!implSrcDir.exists()) {
            throw new MojoFailureException("接口实现模块源代码目录" + implSrcDir + "不存在");
        }

        Set<Artifact> artifacts = project.getArtifacts();
        Map<String, String> depenceProtosdepenceProtos = new HashMap<>();
        if (artifacts != null && !artifacts.isEmpty()) {
            for (Artifact artifact : artifacts) {
                File libFile = artifact.getFile();
                if (!libFile.getName().endsWith(".jar")) {
                    continue;
                }
                try {
                    scanProtos(new JarFile(libFile), depenceProtosdepenceProtos);
                } catch (IOException e) {
                    getLog().warn("parse proto file error", e);
                }
            }
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
                List<Proto> protos = CodeGenertor.parseProtos(protoPath, msg -> getLog().info(msg));
                CodeGenertor.generate(protos, depenceProtosdepenceProtos, srcDir,msg -> getLog().info(msg));
                if (createImpl) {
                    CodeGenertor.generateImpl(protos, implSrcDir, msg -> getLog().info(msg));
                }
            } catch (IOException e) {
                getLog().error(e);
            }
        }
    }

    private void scanProtos(JarFile libJar, Map<String, String> protoEntry) {
        Enumeration<JarEntry> entries = libJar.entries();
        while (entries.hasMoreElements()) {
            JarEntry entry = entries.nextElement();
            StringBuilder sb = new StringBuilder();
            if (entry.getName().startsWith("proto/") && entry.getName().endsWith(".proto")) {
                String name = entry.getName().substring(6);
                try (InputStream inputStream = libJar.getInputStream(entry);
                     BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
                    sb.delete(0, sb.length());
                    String line;
                    while ((line = reader.readLine()) != null) {
                        sb.append(line).append('\n');
                    }
                    String content = sb.toString();
                    protoEntry.put(name, content);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }

            }
        }
    }
}
