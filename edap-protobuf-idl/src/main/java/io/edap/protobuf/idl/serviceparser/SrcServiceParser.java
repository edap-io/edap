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

import com.thoughtworks.qdox.JavaProjectBuilder;
import com.thoughtworks.qdox.model.*;
import io.edap.protobuf.idl.BuildOption;
import io.edap.protobuf.idl.ProtoIdl;
import io.edap.protobuf.idl.ServiceParser;
import io.edap.protobuf.wire.*;
import io.edap.util.CollectionUtils;
import io.edap.util.StringUtil;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.util.*;

import static io.edap.protobuf.idl.util.ProtoIdlUtil.getNoParameterString;
import static io.edap.protobuf.idl.util.ProtoIdlUtil.printProtoIdl;

/**
 * 解析java源代码，根据相关过滤来为接口或者实现类生成prot服务描述对象
 */
public class SrcServiceParser implements ServiceParser {

    private Set<String> srcDirs;
    private String target;
    private Set<String> annotationFilters;

    private Set<String> nameFilters;
    private Map<String, JavaClass> allClass;
    private BuildOption buildeOption;
    private Proto currentProto;

    public SrcServiceParser() {
        this.annotationFilters = new HashSet<>();
        this.nameFilters = new HashSet<>();
        this.srcDirs = new HashSet<>();
        this.allClass = new HashMap<>();
    }

    public SrcServiceParser addSrcDir(String... dirs) {
        if (dirs.length > 0) {
            for (String dir : dirs) {
                srcDirs.add(dir);
            }
        }
        return this;
    }

    public SrcServiceParser setTarget(String target) {
        this.target = target;
        return this;
    }

    public SrcServiceParser addAnnotationFilter(String... annotationNames) {
        if (annotationNames.length > 0) {
            for (String annotationName : annotationNames) {
                annotationFilters.add(annotationName);
            }
        }
        return this;
    }

    public SrcServiceParser addServiceNameFilter(String... names) {
        if (names.length > 0) {
            for (String name : names) {
                nameFilters.add(name);
            }
        }
        return this;
    }

    public Set<JavaClass> getMatchClassList() {
        JavaProjectBuilder builder = new JavaProjectBuilder();
        Set<JavaClass> matchList = new HashSet<>();
        try {
            for (String dir : srcDirs) {
                File fdir = new File(dir);
                Collection<JavaClass> clsList;
                if (fdir.isDirectory()) {
                    builder.addSourceTree(fdir);
                    clsList = builder.getClasses();
                } else {
                    FileReader reader = new FileReader(dir);
                    JavaSource src = builder.addSource(reader);
                    clsList = src.getClasses();
                }

                for (JavaClass cls : clsList) {
                    if (isAnnotationMatch(cls)) {
                        matchList.add(cls);
                    }
                    String fullName;
                    if (!StringUtil.isEmpty(cls.getPackageName())) {
                        fullName = cls.getPackageName() + "." + cls.getSimpleName();
                    } else {
                        fullName = cls.getName();
                    }
                    if (nameFilters.contains(fullName)) {
                        matchList.add(cls);
                    }
                    allClass.put(cls.getName(), cls);
                }
            }
        } catch (FileNotFoundException e) {
            throw new RuntimeException(e);
        }
        return matchList;
    }

    private boolean isAnnotationMatch(JavaClass javaClass) {
        if (CollectionUtils.isEmpty(annotationFilters)) {
            return false;
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

    private String getFullName(JavaClass javaClass) {
        if (StringUtil.isEmpty(javaClass.getPackageName())) {
            return javaClass.getName();
        } else {
            return javaClass.getPackageName() + "." + javaClass.getName();
        }
    }

    @Override
    public ProtoIdl parseServices(BuildOption buildeOption) {
        this.buildeOption = buildeOption;
        ProtoIdl protoIdl = new ProtoIdl();
        Set<JavaClass> serviceClss = getMatchClassList();
        if (CollectionUtils.isEmpty(serviceClss)) {
            return protoIdl;
        }
        Map<String, Proto> dtoProtos = new HashMap<>();
        Map<String, Proto> serviceProtos = new HashMap<>();
        for (JavaClass javaClass : serviceClss) {
            serviceProtos.put(getFullName(javaClass), parseServices(javaClass, dtoProtos));
        }
        protoIdl.setServiceProtos(serviceProtos);
        protoIdl.setDtoProtos(dtoProtos);
        printProtoIdl(protoIdl);
        return protoIdl;
    }

    private Proto parseServices(JavaClass javaClass, Map<String, Proto> dtoProtos) {
        Proto proto = new Proto();
        currentProto = proto;
        proto.setName(getFullName(javaClass));
        proto.setProtoPackage(javaClass.getPackageName());
        List<Option> options = new ArrayList<>();
        options.add(new Option().setName("java_package")
                .setValue(javaClass.getPackageName()));
        Service service = new Service();
        service.setName(javaClass.getName());
        service.setComment(parseComment(javaClass));

        List<JavaMethod> methods = getAllServiceMethods(javaClass);
        List<ServiceMethod> serviceMethods = new ArrayList<>();
        if (!CollectionUtils.isEmpty(methods)) {
            for (JavaMethod javaMethod : methods) {
                serviceMethods.add(buildServiceMethod(javaMethod, dtoProtos));
            }
        }
        service.setMethods(serviceMethods);
        proto.addService(service);
        proto.setOptions(options);
        return proto;
    }

    private ServiceMethod buildServiceMethod(JavaMethod javaMethod, Map<String, Proto> dtoProtos) {
        ServiceMethod serviceMethod = new ServiceMethod();
        serviceMethod.setName(javaMethod.getName());
        serviceMethod.setType(Service.ServiceType.UNARY);
        serviceMethod.setComment(parseComment(javaMethod));
        List<JavaParameter> parameters = javaMethod.getParameters();
        String request = parseRequest(parameters, dtoProtos);
        javaMethod.getTypeParameters();
        return serviceMethod;
    }

    private String parseRequest(List<JavaParameter> parameters, Map<String, Proto> dtoProtos) {
        if (CollectionUtils.isEmpty(parameters)) {
            return getNoParameterString(currentProto, buildeOption);
        }
        int count = parameters.size();
        if (count == 1) {
            JavaParameter parameter = parameters.get(0);
            if (buildeOption.isGrpcCompatible()) {
                parameter.getType();
            }
        }
        return null;
    }

    private Comment parseComment(JavaAnnotatedElement javaMethod) {
        if (!StringUtil.isEmpty(javaMethod.getComment())) {
            Comment comment = new Comment();
            comment.setType(Comment.CommentType.DOCUMENT);
            comment.setLines(Arrays.asList(javaMethod.getComment()));
            List<DocletTag> tags = javaMethod.getTags();
            if (!CollectionUtils.isEmpty(tags)) {
                List<String> lines = new ArrayList<>();
                for (DocletTag tag : tags) {
                    lines.add("@" + tag.getName() + " " + tag.getValue());
                }
                comment.getLines().addAll(lines);
            }
            return comment;
        }
        return null;
    }

    private List<JavaMethod> getAllServiceMethods(JavaClass javaClass) {
        if (javaClass.isInterface()) {
            return javaClass.getMethods(true);
        }
        List<JavaMethod> serviceMethods = new ArrayList<>();
        List<JavaMethod> methods = javaClass.getMethods(true);
        if (CollectionUtils.isEmpty(methods)) {
            return serviceMethods;
        }
        for (JavaMethod javaMethod : methods) {
            if (javaMethod.isPublic() && !javaMethod.isStatic()) {
                serviceMethods.add(javaMethod);
            }
        }
        return serviceMethods;
    }
}
