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

package io.edap.protobuf.idl.util;

import com.thoughtworks.qdox.JavaProjectBuilder;
import com.thoughtworks.qdox.model.JavaAnnotation;
import com.thoughtworks.qdox.model.JavaClass;
import com.thoughtworks.qdox.model.JavaSource;
import io.edap.util.CollectionUtils;

import java.io.FileNotFoundException;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class ProtoIdlGenerator {

    private Set<String> srcDirs;
    private String target;
    private Set<String> annotationFilters;


    public ProtoIdlGenerator() {
        this.annotationFilters = new HashSet<>();
        this.srcDirs = new HashSet<>();
    }

    public ProtoIdlGenerator addSrcDir(String dir) {
        return this;
    }

    public ProtoIdlGenerator addAnnotationFilter(String annotationName) {
        return this;
    }

    public List<String> getFiltedClassName() {
        List<String> list = new ArrayList<>();
        return list;
    }

    public void generate() {
        JavaProjectBuilder builder = new JavaProjectBuilder();
        List<JavaClass> mathList = new ArrayList<>();
        try {
            FileReader reader = new FileReader("");
            JavaSource src = builder.addSource(reader);
            List<JavaClass> clsList = src.getClasses();
            for (JavaClass cls : clsList) {
                if (!isAnnotationMatch(cls)) {
                    continue;
                }
                mathList.add(cls);
            }
        } catch (FileNotFoundException e) {
            throw new RuntimeException(e);
        }

    }

    private boolean isAnnotationMatch(JavaClass javaClass) {
        if (CollectionUtils.isEmpty(annotationFilters)) {
            return true;
        }
        List<JavaAnnotation> anns = javaClass.getAnnotations();
        if (CollectionUtils.isEmpty(anns)) {
            return false;
        }
        for (JavaAnnotation ann : anns) {
            if (annotationFilters.contains(ann.getType().getName())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 设置生成proto文件的目标路径
     * @param target
     * @return
     */
    public ProtoIdlGenerator setTarget(String target) {
        this.target = target;
        return this;
    }
}
