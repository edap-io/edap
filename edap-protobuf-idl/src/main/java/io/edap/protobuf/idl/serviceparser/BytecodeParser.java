/*
 * Copyright 2022 The edap Project
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

package io.edap.protobuf.idl.serviceparser;

import io.edap.protobuf.idl.BuildOption;
import io.edap.protobuf.idl.ProtoIdl;
import io.edap.protobuf.idl.ServiceParser;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.ParameterNode;

import java.io.IOException;
import java.io.InputStream;
import java.util.Enumeration;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

public class BytecodeParser implements ServiceParser {

    /**
     * jar包的路径信息
     */
    private String jarFilePath;

    public BytecodeParser() {

    }

    public BytecodeParser(String jarFilePath) {
        this.setJarFilePath(jarFilePath);
    }

    @Override
    public ProtoIdl parseServices(BuildOption buildeOption) {
        String bytes = null;
        ProtoIdl protoIdl = new ProtoIdl();
        try {
            JarFile jarFile = new JarFile(jarFilePath);
            Enumeration<JarEntry> entries =  jarFile.entries();
            if (entries == null) {
                return null;
            }
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                if (entry.getName().indexOf("META-INF") != -1 || "module-info.class".equals(entry.getName())) {
                    continue;
                }
                getServiceInterface(jarFile, entry);
            }
        } catch (IOException e) {
            System.out.println("jarFilePath=" + jarFilePath);
            throw new RuntimeException(e);
        }

        return protoIdl;
    }

    private void getServiceInterface(JarFile jarFile, JarEntry entry) throws IOException {
        if (entry.isDirectory() || !entry.getName().endsWith(".class")) {
            return;
        }
        InputStream input = jarFile.getInputStream(entry);
        try {
            ClassReader cr = new ClassReader(input);
            ClassNode annNode = new ClassNode();
            cr.accept(annNode, 0);
            List<AnnotationNode> anns = annNode.visibleAnnotations;
            //System.out.println("entry.getName=" + entry.getName());
            if (anns == null || anns.isEmpty()) {
                //System.out.println("anns is empty");
            } else {
                for (AnnotationNode ann : anns) {
                    if ("Lcom/yonyou/cloud/middleware/rpc/RemoteCall;".equals(ann.desc)) {
                        System.out.println(jarFile.getName());
                        System.out.println(entry.getName());
                        System.out.println(ann.desc);

                        List<MethodNode> methods = annNode.methods;
                        if (methods != null && !methods.isEmpty()) {
                            for (MethodNode methodNode : methods) {
                                StringBuilder params = new StringBuilder();
                                if (methodNode.parameters != null) {
                                    for (ParameterNode pn : (List<ParameterNode>)methodNode.parameters) {
                                        if (params.length() > 0) {
                                            params.append(',');
                                        }
                                        params.append(pn.name);
                                    }
                                }

                                System.out.println("method: " + methodNode.name + "(" + params + ") " + methodNode.desc);
                                System.out.println("  desc: " + methodNode.signature);
                            }
                        }
                    }
                }
            }

            //System.out.println("-----------------------");
        } catch (Throwable t) {
            System.out.println("=======" + entry.getName());
            t.printStackTrace();
        }
        //cr.accept(protoIdlClassVisitor, ClassReader.SKIP_DEBUG);
    }

    /**
     * jar包的路径信息
     */
    public String getJarFilePath() {
        return jarFilePath;
    }

    public void setJarFilePath(String jarFilePath) {
        this.jarFilePath = jarFilePath;
    }
}
