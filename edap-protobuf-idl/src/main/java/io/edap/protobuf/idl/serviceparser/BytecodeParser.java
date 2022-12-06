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

import io.edap.log.Logger;
import io.edap.log.LoggerManager;
import io.edap.protobuf.idl.BuildOption;
import io.edap.protobuf.idl.ProtoIdl;
import io.edap.protobuf.idl.ServiceParser;
import io.edap.protobuf.idl.model.*;
import io.edap.protobuf.idl.util.ClassVisitorUtil;
import io.edap.protobuf.wire.*;
import io.edap.util.CollectionUtils;
import io.edap.util.StringUtil;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.IOException;
import java.io.InputStream;
import java.util.*;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import static io.edap.protobuf.idl.util.ClassVisitorUtil.parseMethodDesc;
import static io.edap.protobuf.idl.util.ProtoIdlUtil.*;
import static io.edap.protobuf.util.ProtoUtil.needEncode;
import static io.edap.protobuf.util.ProtoUtil.parentMapType;
import static io.edap.util.AsmUtil.toInternalName;
import static io.edap.util.AsmUtil.toLangName;

public class BytecodeParser implements ServiceParser {

    Logger log = LoggerManager.getLogger(BytecodeParser.class);

    /**
     * jar包的路径信息
     */
    private String jarFilePath;
    private JarFile jarFile;

    private Set<String> annotationFilters;

    private Set<String> nameFilters;
    private int entriesCount;

    private BytecodeParser() {
        this.annotationFilters = new HashSet<>();
        this.nameFilters = new HashSet<>();
        this.setEntriesCount(0);
    }

    public BytecodeParser(String jarFilePath) {
        this();
        this.setJarFilePath(jarFilePath);
        try {
            openFile();
        } catch (Exception e) {

        }
    }

    private void openFile() throws IOException {
        this.jarFile = new JarFile(jarFilePath);
    }

    public BytecodeParser addAnnotationFilter(String... annotationNames) {
        if (annotationNames.length > 0) {
            for (String annotationName : annotationNames) {
                annotationFilters.add(annotationName);
            }
        }
        return this;
    }

    public BytecodeParser addServiceNameFilter(String... names) {
        if (names.length > 0) {
            for (String name : names) {
                nameFilters.add(name);
            }
        }
        return this;
    }

    @Override
    public ProtoIdl parseServices(BuildOption buildOption) {
        ProtoIdl protoIdl = new ProtoIdl();
        try {
            Enumeration<JarEntry> entries = jarFile.entries();
            if (entries == null) {
                return null;
            }
            setEntriesCount(0);
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                setEntriesCount(getEntriesCount() + 1);
                if (entry.getName().indexOf("META-INF") != -1
                        || "module-info.class".equals(entry.getName())) {
                    continue;
                }
                getServiceInterface(jarFile, entry, protoIdl, buildOption);
            }
        } catch (IOException e) {
            System.out.println("jarFilePath=" + jarFilePath);
            throw new RuntimeException(e);
        }

        return protoIdl;
    }

    @Override
    public void buildBeanProto(String clazzName, String serviceName, BuildOption buildOption, ProtoIdl protoIdl) {
//        try {
//            System.out.println("clazzName=" + clazzName);
//        } catch (Throwable t) {
//            t.printStackTrace();
//        }
        String path = toInternalName(clazzName) + ".class";
        JarEntry entry = jarFile.getJarEntry(path);
        Map<String, Proto> dtoProtos = getDtoProtos(protoIdl);
        Proto dtoProto = dtoProtos.get(getPackageName(clazzName) + ".proto");
        String packName = getPackageName(clazzName);
        String protoName = packName + ".proto";
        if (dtoProto != null) {
            Message msg = dtoProto.getMessage(getSimpleName(clazzName));
            if (msg != null) {
                return;
            }
        } else {
            dtoProto = new Proto();
            dtoProto.setName(protoName);
            setProtoPackage(dtoProto, packName);
            dtoProto.setProtoPackage(packName);
            dtoProtos.put(protoName, dtoProto);
        }
        if (entry == null) {
            return;
        }
        InputStream input = null;
        try {
            input = jarFile.getInputStream(entry);
            ClassReader cr = new ClassReader(input);
            ClassNode annNode = new ClassNode();
            cr.accept(annNode, 0);
            ClassVisitorUtil.ClazzSignature cSignture = null;
            if (!StringUtil.isEmpty(annNode.signature)) {
                ClassVisitorUtil.SignatureParser signtureParser =
                        new ClassVisitorUtil.SignatureParser(annNode.signature);
                cSignture = signtureParser.parse();
            }
            List<FieldNode> fields = annNode.fields;
            Message msg = new Message();
            msg.setName(getSimpleName(clazzName));
            List<Field> protoFields = new ArrayList<>();
            msg.setFields(protoFields);
            String parentClazzName = annNode.superName;
            Field parentMapField = null;

            int tag = 1;
            dtoProto.addMsg(msg);
            ParentInfo parentInfo = new ParentInfo();
            if (cSignture != null && !CollectionUtils.isEmpty(cSignture.getFormalTypes())) {
                List<String> generics = new ArrayList<>();
                for (FormalType ft : cSignture.getFormalTypes()) {
                    generics.add(ft.getFormal());
                }
                parentInfo.setGenerics(generics);
            }
            parentInfo.setServiceName(serviceName);
            parentInfo.setMessageName(packName + "." + msg.getName());
            for (FieldNode fieldNode : fields) {
                if (!needEncode(fieldNode)) {
                    continue;
                }
                Field field = new Field();
                field.setTag(tag);
                field.setName(fieldNode.name);
                String desc;
                if (!StringUtil.isEmpty(fieldNode.signature)) {
                    desc = fieldNode.signature;
                    if ("Ljava/lang/Object;".equals(fieldNode.desc)) {
                        desc = fieldNode.desc;
                    }
                } else {
                    desc = fieldNode.desc;
                }
                ClassVisitorUtil.FieldDescParser fdp = new ClassVisitorUtil.FieldDescParser(desc);
                ClassVisitorUtil.FieldDesc fdesc = fdp.parse();
                IdlFieldType type = toProtoIdlType(fdesc.getType(), parentInfo, buildOption, protoIdl, this);
                field.setCardinality(type.getCardinality());
                String typeName = type.getType();
                if (packName.equals(getPackageName(typeName))) {
                    typeName = getSimpleName(typeName);
                }
                if (!CollectionUtils.isEmpty(type.getOptions())) {
                    field.setOptions(type.getOptions());
                }
                field.setType(typeName);
                tag++;
                protoFields.add(field);
                //System.out.println("fieldNode=" + fieldNode);
            }
            if (parentClazzName.startsWith("java/")) {
                Class parentClazz = Class.forName(toLangName(parentClazzName));
                java.lang.reflect.Type mapType = parentMapType(parentClazz);
                if (mapType != null) {
                    parentMapField = new Field();
                    parentMapField.setTag(WireFormat.RESERVED_TAG_VALUE_START);
                    Comment comment = new Comment();
                    comment.setType(Comment.CommentType.INLINE);
                    comment.setLines(Arrays.asList("该类的父类为" + cSignture.getParentType()));
                    parentMapField.setComment(comment);
                    setBeanParentMap(parentMapField, cSignture, parentInfo, buildOption, protoIdl);
                    protoFields.add(parentMapField);
                }
            }
        } catch (Throwable t) {
            log.error("", t);
        } finally {
            if (input != null) {
                try {input.close();} catch (IOException e) {}
            }
        }

    }

    private void setBeanParentMap(Field parentMapField,
                                  ClassVisitorUtil.ClazzSignature cSignture,
                                  ParentInfo parentInfo,
                                  BuildOption buildOption,
                                  ProtoIdl protoIdl) {
        if (cSignture == null || cSignture.getParentType() == null) {
            return;
        }
        if (cSignture.getParentType() instanceof IdlParameterizedType) {
            IdlParameterizedType ipt = (IdlParameterizedType)cSignture.getParentType();
            IdlJavaType[] iptArgs = ipt.ActualTypeArgs();
            if (iptArgs != null && iptArgs.length == 2) {
                if (isMapKeySupport(iptArgs[0]) && isMapValSupport(iptArgs[1])) {
                    StringBuilder type = new StringBuilder();
                    IdlFieldType keyIft = toProtoIdlType(iptArgs[0], parentInfo, buildOption,
                            protoIdl, this);
                    type.append("map<").append(keyIft.getType());
                    IdlFieldType valIft = toProtoIdlType(iptArgs[1], parentInfo, buildOption,
                            protoIdl, this);
                    type.append(',').append(valIft.getType()).append('>');
                    parentMapField.setType(type.toString());
                    parentMapField.setName("_parent");
                    List<Option> options = new ArrayList<>();
                    options.add(new Option().setName("java_type").setValue(ipt.toString()));
                    parentMapField.setOptions(options);
                }
            }
        }
    }

    private void getServiceInterface(JarFile jarFile, JarEntry entry, ProtoIdl protoIdl,
                                     BuildOption buildOption) throws IOException {
        if (entry.isDirectory() || !entry.getName().endsWith(".class")) {
            return;
        }
        InputStream input = null;
        try {
            input = jarFile.getInputStream(entry);
            ClassReader cr = new ClassReader(input);
            ClassNode annNode = new ClassNode();
            cr.accept(annNode, 0);
            List<AnnotationNode> anns = annNode.visibleAnnotations;
            if (!isAnnotationMatch(anns)) {
                return;
            }
            List<MethodNode> methods = annNode.methods;
            Proto proto = new Proto();
            String clazzName = getServiceProtoName(cr.getClassName());
            String packageName = getPackageName(clazzName);
            String simpleName = getSimpleName(clazzName);
            String protoName  = clazzName + ".proto";
            proto.setName(protoName);
            proto.setProtoPackage(packageName);
            List<Option> opitons = new ArrayList<>();
            opitons.add(new Option().setName("java_package").setValue(packageName));
            proto.setOptions(opitons);
            Map<String, Proto> protos = protoIdl.getServiceProtos();
            if (protos == null) {
                protos = new HashMap<>();
                protoIdl.setServiceProtos(protos);
            }
            protos.put(protoName, proto);
            Service service = new Service();
            List<Service> services = new ArrayList<>();
            services.add(service);
            proto.setServices(services);
            service.setName(simpleName);
            List<ServiceMethod> serviceMethods = new ArrayList<>();
            service.setMethods(serviceMethods);
            if (CollectionUtils.isEmpty(methods)) {
                return;
            }
            for (MethodNode methodNode : methods) {
                IdlMethod idlMethod = parseMethodDesc(methodNode);
                ServiceMethod serviceMethod = new ServiceMethod();
                serviceMethod.setName(methodNode.name);
                serviceMethod.setType(Service.ServiceType.UNARY);
                serviceMethod.setRequest(buildIdlRequest(idlMethod.getMethodParams(), clazzName,
                        buildOption, protoIdl, this));
                serviceMethod.setResponse(buildIdlResp(idlMethod.getReturns(), clazzName,
                        idlMethod.getReturnType(), buildOption, protoIdl, this));
                serviceMethods.add(serviceMethod);
            }

        } catch (Throwable t) {
            log.error("getServiceInterface error", t);
        } finally {
            if (input != null) {
                try {input.close();} catch (IOException e) {}
            }

        }
    }

    private boolean isAnnotationMatch(List<AnnotationNode> annotationNodes) {
        if (CollectionUtils.isEmpty(annotationFilters)) {
            return false;
        }
        if (CollectionUtils.isEmpty(annotationNodes)) {
            return false;
        }
        for (AnnotationNode annNode : annotationNodes) {
            if (annotationFilters.contains(annNode.desc)) {
                return true;
            }
        }
        return false;
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

    public int getEntriesCount() {
        return entriesCount;
    }

    public void setEntriesCount(int entriesCount) {
        this.entriesCount = entriesCount;
    }
}
