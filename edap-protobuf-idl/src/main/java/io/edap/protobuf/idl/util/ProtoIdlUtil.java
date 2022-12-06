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

import io.edap.protobuf.ProtoBuf;
import io.edap.protobuf.builder.ProtoBuilder;
import io.edap.protobuf.builder.ProtoV3Builder;
import io.edap.protobuf.idl.ProtoIdl;
import io.edap.protobuf.idl.ServiceParser;
import io.edap.protobuf.idl.model.*;
import io.edap.protobuf.idl.model.impl.IdlJavaClass;
import io.edap.protobuf.model.MessageInfo;
import io.edap.protobuf.model.ProtoTypeInfo;
import io.edap.protobuf.idl.BuildOption;
import io.edap.protobuf.idl.UnsupportDataType;
import io.edap.protobuf.util.ProtoTagComparator;
import io.edap.protobuf.util.ProtoUtil;
import io.edap.protobuf.wire.*;
import io.edap.util.CollectionUtils;
import io.edap.util.StringUtil;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.*;

import static io.edap.protobuf.util.ProtoUtil.javaToProtoType;
import static io.edap.util.AsmUtil.toLangName;
import static io.edap.util.CryptUtil.md5;

/**
 * 使用Proto文件定义service的相关处理逻辑
 */
public class ProtoIdlUtil {

    private ProtoIdlUtil() {}

    /**
     * 根据给定的服务接口的类生成该接口对应的微服的proto描述对象列表
     * @param iface 服务接口名
     * @param dtoProtos 现有的dto的Proto对象列表
     * @return
     * @throws IOException
     */
    public static Proto buildServiceProto(Class iface, Map<String, Proto> dtoProtos) throws IOException {
        BuildOption defaultOption = new BuildOption();
        return buildServiceProto(iface, dtoProtos, defaultOption);
    }

    /**
     * 根据给定的服务接口的类生成该接口对应的微服的proto描述对象列表
     * @param iface 服务接口名
     * @param buildOption 构建选项
     * @return
     * @throws IOException
     */
    public static Proto buildServiceProto(Class iface, Map<String, Proto> dtoProtos, BuildOption buildOption)
            throws IOException {
        if (dtoProtos == null) {
            throw new NullPointerException("dtoProtos can't null");
        }
        if (iface == null) {
            throw new NullPointerException("interface can't null");
        }
        // 生成proto文件的包名
        String packName = iface.getPackage().getName();
        Proto proto = new Proto();
        proto.setName(iface.getName());
        // 设置protocol buffer的版本
        proto.setSyntax(Syntax.PROTO_3);
        proto.setProtoPackage(packName);

        List<Option> options = new ArrayList<>();
        Option javaPackage = new Option().setName("java_package").setValue(packName);
        options.add(javaPackage);

        // 解析接口的方法，把方法生成proto文件的描述信息
        Method[] methods = iface.getDeclaredMethods();
        Service service = new Service();
        service.setName(iface.getSimpleName());
        proto.addService(service);
        for (Method method : methods) {
            parseMethodInfo(method, proto, service, dtoProtos, buildOption);
        }
        proto.setOptions(options);
        return proto;
    }

    /**
     * 如果服务的函数没有参数时返回的proto服务定义的字符串，如果兼容gRPC时返回Empty的对象，如果不兼容则字节返回空的字符串
     * @param proto proto的对象
     * @param buildOption 构建对象的选项设置
     * @return
     */
    public static String getNoParameterString(Proto proto, BuildOption buildOption) {
        String request;
        if (buildOption.isGrpcCompatible()) {
            List<String> impts = proto.getImports();
            if (!impts.contains("edap-idl/Empty.proto")) {
                impts.add("edap-idl/Empty.proto");
            }
            request = "Empty";
        } else {
            request = "";
        }
        return request;
    }

    public static void printProtoIdl(ProtoIdl protoIdl) {
        Map<String, Proto> serviceProtos = protoIdl.getServiceProtos();
        if (!CollectionUtils.isEmpty(serviceProtos)) {
            for (Map.Entry<String, Proto> entry : serviceProtos.entrySet()) {
                System.out.println(entry.getKey() + "\n");
                ProtoBuilder protoBuilder = new ProtoV3Builder(entry.getValue());
                System.out.println(protoBuilder.toProtoString());

            }
        }
    }

    /**
     * 由java的Type，返回proto服务定义的字符串表示，如果是javaBean则返回名称，如果JavaBean和服务包名不同则将包名命名的proto对象添加的扩展
     * proto对象添加到dtoPotos的Map中
     *
     * @param type java的类型数据
     * @param proto proto对象
     * @param buildOption 构建proto选项
     * @param dtoProtos 扩展的dto的proto对象Map
     * @return
     * @throws IOException
     */
    private static String getJavaTypeProtoString(Type type, Proto proto, BuildOption buildOption, Map<String, Proto> dtoProtos) throws IOException {
        ProtoTypeInfo respProtoType = javaToProtoType(type);
        MessageInfo msgInfo = respProtoType.getMessageInfo();
        String typeName;
        if (msgInfo != null) {
            typeName = msgInfo.getMessageName();
            if (!StringUtil.isEmpty(msgInfo.getImpFile())) {
                List<String> imps = proto.getImports();
                if (!imps.contains(msgInfo.getImpFile())) {
                    imps.add(msgInfo.getImpFile());
                }
            }
        } else {
            typeName = getProtoMessageClass(type).getSimpleName();
            buildClassMessage(getProtoMessageClass(type), proto, dtoProtos);
        }
        return typeName;
    }

    /**
     * 解析接口的Method生成proto服务定义的ServiceMethod对象并将添加到Proto的service中 s
     * @param method java的Method对象
     * @param proto Proto对象
     * @param service 服务的对象
     * @param dtoProtos DTO扩展的Proto对象
     * @param buildOption 构建proto文件的选项
     * @throws IOException
     */
    private static void parseMethodInfo(Method method, Proto proto, Service service,
                                               Map<String, Proto> dtoProtos, BuildOption buildOption) throws IOException {
        Type[] paramTypes = method.getGenericParameterTypes();
        String request, response;
        ServiceMethod serviceMethod;
        System.out.println(method.getName());
        if (paramTypes.length == 0) {
            request = getNoParameterString(proto, buildOption);
        } else {
            StringBuilder sb = new StringBuilder();
            String typeName;
            for (Type type : paramTypes) {
                typeName = getJavaTypeProtoString(type, proto, buildOption, dtoProtos);
                if (sb.length() > 0) {
                    sb.append(",");
                }
                sb.append(typeName);
            }
            request = sb.toString();
        }
        Type respType = method.getGenericReturnType();
        response = getJavaTypeProtoString(respType, proto, buildOption, dtoProtos);
        serviceMethod = new ServiceMethod()
                .setName(method.getName())
                .setType(Service.ServiceType.UNARY)
                .setRequest(request)
                .setResponse(response);
        service.addMethod(serviceMethod);
    }

    /**
     * 根据给定的javaBean的class生成该Class对应的Message添加到Proto的对象中，如果该javaBean中有其他的Message如果有其他包名的则生成新的
     * 的Proto对象列表返回
     * @param msgCls
     * @param proto
     * @param dtoProtos
     * @return
     * @throws
     */
    public static void buildClassMessage(Class msgCls, Proto proto, Map<String, Proto> dtoProtos) throws IOException {
        Message msg = new Message();
        String pkgName = msgCls.getPackage().getName();
        String protoPkgName = getProtoJavaPackageName(proto);
        Proto curProto = proto;
        if (!pkgName.equals(protoPkgName)) {
            String protoName = pkgName + ".dto";
            curProto = dtoProtos.get(protoName);
            if (curProto == null) {
                curProto = new Proto();
                curProto.setProtoPackage(pkgName);
                curProto.addOption(new Option().setName("java_package").setValue(pkgName));
                dtoProtos.put(protoName, curProto);
            }
        }
        curProto.addMsg(msg);
        msg.setName(msgCls.getSimpleName());
        List<ProtoBuf.ProtoFieldInfo> protoFieldInfos = ProtoUtil.getProtoFields(msgCls);
        if (CollectionUtils.isEmpty(protoFieldInfos)) {
            return;
        }
        ProtoTagComparator ptc = new ProtoTagComparator();
        Collections.sort(protoFieldInfos, ptc);
        for (ProtoBuf.ProtoFieldInfo fieldInfo : protoFieldInfos) {
            Field field = new Field();
            field.setName(fieldInfo.field.getName());
            field.setTag(fieldInfo.protoField.tag());
            field.setCardinality(fieldInfo.protoField.cardinality());
            field.setOptions(parseOptions(fieldInfo.protoField.options()));
            if (fieldInfo.protoField.type() == Field.Type.MESSAGE) {
                field.setType(getProtoMessageClass(fieldInfo.field.getGenericType()).getName());
                Class fieldCls = getProtoMessageClass(fieldInfo.field.getGenericType());
                Proto fieldProto = dtoProtos.get(fieldCls.getPackage().getName());
                if (fieldProto == null || !protoHasMessage(fieldProto, fieldCls.getSimpleName())) {
                    buildClassMessage(fieldCls, curProto, dtoProtos);
                }
            } else {
                field.setType(fieldInfo.protoField.type().value());
            }
            msg.addField(field);
        }
    }

    private static boolean protoHasMessage(Proto proto, String name) {
        Map<String, Message> msgs = proto.getMessageMap();
        return msgs.containsKey(name);
    }

    /**
     * 根据java的Type获取该类型针对Proto的class对象，如果是List，数组以及泛型，将内部包含的Class返回。只针对事javabean的情况
     * @param type
     * @return
     */
    private static Class getProtoMessageClass(Type type) {
        if (type instanceof Class) {
            Class cls = (Class)type;
            if (cls.isArray()) {
                return cls.getComponentType();
            }
            return (Class)type;
        } else if (type instanceof ParameterizedType) {
            ParameterizedType ptype = (ParameterizedType)type;
            Type[] types = ptype.getActualTypeArguments();
            if (types.length > 0) {
                if (types[0] instanceof Class) {
                    return (Class)types[0];
                }
            }
        }
        throw new UnsupportDataType("unsupport data type " + type.getTypeName() + "[" + type + "]");
    }

    /**
     * 解析注解中使用逗号分割的字符串表示的Option列表
     * @param option 用都好分割的Option
     * @return
     */
    private static List<Option> parseOptions(String[] option) {
        List<Option> options = new ArrayList<>();
        if (option == null) {
            return options;
        }
        for (String optionStr : option) {
            int equalIndex = optionStr.indexOf("=");
            if (equalIndex != -1) {
                String name = optionStr.substring(0, equalIndex).trim();
                String value = optionStr.substring(equalIndex + 1).trim();
                options.add(new Option().setName(name).setValue(value));
            }
        }
        return options;
    }

    /**
     * 获取Proto对像中java的包名
     * @param proto
     * @return
     */
    private static String getProtoJavaPackageName(Proto proto) {
        String name = proto.getProtoPackage();
        List<Option> options = proto.getOptions();
        if (CollectionUtils.isEmpty(options)) {
            return name;
        }
        for (Option option : options) {
            if (!"java_package".equals(option.getName())) {
                continue;
            }
            if (!StringUtil.isEmpty(option.getValue())) {
                name = option.getValue();
            }
        }

        return name;
    }

    public static String getServiceProtoName(String clazzName) {
        if (clazzName.startsWith("L") && clazzName.endsWith(";")) {
            return toLangName(clazzName.substring(0, clazzName.length()-1));
        }
        return toLangName(clazzName);
    }

    public static String getPackageName(String clazzName) {
        int lastDot = clazzName.lastIndexOf(".");
        if (lastDot == - 1) {
            return "";
        } else {
            return clazzName.substring(0, lastDot);
        }
    }

    public static String getSimpleName(String clazzName) {
        int lastDot = clazzName.lastIndexOf(".");
        if (lastDot == - 1) {
            return clazzName;
        } else {
            return clazzName.substring(lastDot+1);
        }
    }

    private static String buildNoParam(String serviceName, ProtoIdl protoIdl, BuildOption buildOption) {
        StringBuilder request = new StringBuilder();
        if (buildOption.isGrpcCompatible()) {
            addServiceImport(serviceName, protoIdl, "google/protobuf/empty.proto");
            request.append("google.protobuf.Empty");
        } else {
            addServiceImport(serviceName, protoIdl, "edap-idl/empty.proto");
            request.append("io.edap.protobuf.idl.proto.Empty");
            return request.toString();
        }
        return request.toString();
    }

    public static String buildIdlRequest(List<MethodParam> params,
                                         String serviceName,
                                         BuildOption buildOption,
                                         ProtoIdl protoIdl,
                                         ServiceParser serviceParser) {

        if (CollectionUtils.isEmpty(params)) {
            return buildNoParam(serviceName, protoIdl, buildOption);
        }
        StringBuilder request = new StringBuilder();
        if (params.size() == 1) {
            String name = baseTypeToProtoIdlType(params.get(0).getIdlJavaClass(), serviceName, buildOption,
                    protoIdl, serviceParser);
            if (StringUtil.isEmpty(name)) {
                request.append(buildCombineReqMessage(params, serviceName, buildOption, protoIdl,
                        serviceParser));
            } else {
                request.append(name);
            }
        } else {
            request.append(buildCombineReqMessage(params, serviceName, buildOption, protoIdl, serviceParser));
        }
        return request.toString();
    }

    public static String baseTypeToProtoIdlType(IdlJavaType idlJavaType,
                                                String serviceName,
                                                BuildOption buildOption,
                                                ProtoIdl protoIdl,
                                                ServiceParser serviceParser) {
        String protoType;
        String impt = null;
        String clazzName;
        if (idlJavaType instanceof IdlParameterizedType) {
            IdlParameterizedType ipt = (IdlParameterizedType)idlJavaType;
            clazzName = ipt.canonicalName();
        } else {
            clazzName = idlJavaType.canonicalName();
        }
        String dtoPkgName = getPackageName(clazzName);
        switch (clazzName) {
            case "int":
                protoType = "Int32Value";
                if (buildOption.isGrpcCompatible()) {
                    impt = "google/protobuf/wrappers.proto";
                } else {
                    impt = "edap-idl/wrappers.proto";
                }
                break;
            case "long":
                protoType = "Int64Value";
                if (buildOption.isGrpcCompatible()) {
                    impt = "google/protobuf/wrappers.proto";
                } else {
                    impt = "edap-idl/wrappers.proto";
                }
                break;
            case "float":
                protoType = "FloatValue";
                if (buildOption.isGrpcCompatible()) {
                    impt = "google/protobuf/wrappers.proto";
                } else {
                    impt = "edap-idl/wrappers.proto";
                }
                break;
            case "double":
                protoType = "DoubleValue";
                if (buildOption.isGrpcCompatible()) {
                    impt = "google/protobuf/wrappers.proto";
                } else {
                    impt = "edap-idl/wrappers.proto";
                }
                break;
            case "java.lang.String":
                protoType = "StringValue";
                if (buildOption.isGrpcCompatible()) {
                    impt = "google/protobuf/wrappers.proto";
                } else {
                    impt = "edap-idl/wrappers.proto";
                }
                break;
            case "boolean":
                protoType = "BoolValue";
                if (buildOption.isGrpcCompatible()) {
                    impt = "google/protobuf/wrappers.proto";
                } else {
                    impt = "edap-idl/wrappers.proto";
                }
                break;
            case "[B":
                protoType = "BytesValue";
                if (buildOption.isGrpcCompatible()) {
                    impt = "google/protobuf/wrappers.proto";
                } else {
                    impt = "edap-idl/wrappers.proto";
                }
                break;
            case "void":
                protoType = "Empty";
                if (buildOption.isGrpcCompatible()) {
                    impt = "google/protobuf/empty.proto";
                } else {
                    impt = "edap-idl/empty.proto";
                }
                break;
            default:   // 除了基本类型，判断是否是javabean，如果是javabean直接把JavaBean作为Proto的message
                if (clazzName.startsWith("java.")) {
                    protoType = "";
                } else {
                    protoType = clazzName;
                    serviceParser.buildBeanProto(clazzName, serviceName, buildOption, protoIdl);
                    impt = dtoPkgName;
                }
                break;

        }
        if (!StringUtil.isEmpty(impt)) {
            addServiceImport(serviceName, protoIdl, impt);
        }
        return protoType;
    }

    public static IdlFieldType buildMapMsg(IdlJavaType mapJavaType,
                                           ParentInfo parentInfo,
                                           BuildOption buildOption,
                                           ProtoIdl protoIdl,
                                           ServiceParser serviceParser) {
        String pkgName = getPackageName(parentInfo.getMessageName());
        IdlParameterizedType pitemType = (IdlParameterizedType)mapJavaType;
        Proto dtoProto = getDtoProtos(protoIdl).get(pkgName + ".proto");
        IdlJavaClass itemRawType = (IdlJavaClass) pitemType.rawType();
        IdlJavaType[] argTypes = pitemType.ActualTypeArgs();
        StringBuilder types = new StringBuilder();
        for (IdlJavaType type : argTypes) {
            types.append(type.toString());
        }
        String mapMsgName = "MapMsg_" + md5(types.toString());
        Message msg = new Message();
        msg.setName(mapMsgName);
        List<Field> fields = new ArrayList<>();
        msg.setFields(fields);
        dtoProto.getMessages().add(msg);
        int tag = 1;
        for (IdlJavaType type : argTypes) {
            Field field = new Field();
            if (tag == 1) {
                field.setName("key").setTag(tag);
            } else if (tag == 2) {
                field.setName("value").setTag(tag);
            }
            IdlFieldType ift = toProtoIdlType(type, parentInfo, buildOption, protoIdl, serviceParser);
            String protoType = ift.getType();
            if (getPackageName(protoType).equals(getPackageName(parentInfo.getMessageName()))) {
                protoType = getSimpleName(protoType);
            }
            field.setCardinality(ift.getCardinality());
            field.setType(protoType);
            fields.add(field);
            tag++;
        }
        IdlFieldType ift = new IdlFieldType();
        ift.setType(pkgName + "." + mapMsgName);
        return ift;
    }

    public static IdlFieldType toProtoIdlType(IdlJavaType idlJavaType,
                                              ParentInfo parentInfo,
                                              BuildOption buildOption,
                                              ProtoIdl protoIdl,
                                              ServiceParser serviceParser) {
        String protoType;
        String impt = "";
        String clazzName;
        IdlJavaType[] argTypes = null;
        IdlFieldType ift = new IdlFieldType();
        if (idlJavaType instanceof IdlParameterizedType) {
            IdlParameterizedType ipt = (IdlParameterizedType)idlJavaType;
            clazzName = ipt.rawType().canonicalName();
            argTypes = ipt.ActualTypeArgs();
        } else {
            IdlJavaClass idlJavaClass = (IdlJavaClass)idlJavaType;
            if (idlJavaClass.isArray()) {
                IdlFieldType fieldType = toProtoIdlType(idlJavaClass.getComponentType(), parentInfo, buildOption, protoIdl,
                        serviceParser);
                if (fieldType.getCardinality() != Field.Cardinality.REPEATED) {
                    ift.setType(fieldType.getType());
                    ift.setCardinality(Field.Cardinality.REPEATED);
                    return ift;
                }
                throw new RuntimeException("type [" + idlJavaType.canonicalName() + "] not supported");
            } else {
                clazzName = idlJavaType.canonicalName();
            }
        }
        if (!CollectionUtils.isEmpty(parentInfo.getGenerics())
                && parentInfo.getGenerics().contains(clazzName)) {
            protoType = "Any";
            if (buildOption.isGrpcCompatible()) {
                impt = "google/protobuf/Any.proto";
            } else {
                protoType = "object";
            }
            ift.setType(protoType);
            if (!StringUtil.isEmpty(impt)) {
                addServiceImport(parentInfo.getServiceName(), protoIdl, impt);
            }
            if (!StringUtil.isEmpty(parentInfo.getMessageName()) && !StringUtil.isEmpty(impt)) {
                addDtoImport(parentInfo.getMessageName(), protoIdl, impt);
            }
            return ift;
        }
        String dtoPkgName = getPackageName(clazzName);
        switch (clazzName) {
            case "int":
            case "java.lang.Integer":
                protoType = "int32";
                break;
            case "long":
            case "java.lang.Long":
                protoType = "int64";
                break;
            case "float":
            case "Float":
                protoType = "float";
                break;
            case "double":
            case "Double":
                protoType = "double";
                break;
            case "java.lang.String":
                protoType = "string";
                break;
            case "boolean":
            case "Boolean":
                protoType = "bool";
                break;
            case "[B":
                protoType = "bytes";
                break;
            case "void":
                protoType = "Empty";
                if (buildOption.isGrpcCompatible()) {
                    impt = "google/protobuf/empty.proto";
                } else {
                    impt = "edap-idl/empty.proto";
                }
                break;
            case "java.lang.Object":
                protoType = "Any";
                if (buildOption.isGrpcCompatible()) {
                    impt = "google/protobuf/Any.proto";
                } else {
                    protoType = "object";
                }
                break;
            case "java.util.Date":
                protoType = "int64";
                List<Option> options = new ArrayList<>();
                options.add(new Option().setName("java_class").setValue(idlJavaType.canonicalName()));
                ift.setOptions(options);
                break;
            case "java.util.List":
            case "java.util.Set":
            case "java.lang.Iterable":
                ift.setCardinality(Field.Cardinality.REPEATED);
                if (argTypes != null) {
                    if (argTypes[0] instanceof IdlParameterizedType) {
                        IdlParameterizedType pitemType = (IdlParameterizedType)argTypes[0];
                        IdlJavaClass itemRawType = (IdlJavaClass) pitemType.rawType();
                        if ("java.util.Map".equals(itemRawType.canonicalName())) {
                            IdlFieldType fieldType = buildMapMsg(pitemType, parentInfo, buildOption, protoIdl,
                                    serviceParser);
                            protoType = fieldType.getType();
                        } else {
                            protoType = "";
                        }
                    } else {
                        IdlFieldType fieldType = toProtoIdlType(argTypes[0], parentInfo, buildOption, protoIdl,
                                serviceParser);
                        protoType = fieldType.getType();
                    }
                } else {
                    protoType = "";
                }
                break;
            case "java.util.Map":
                if (argTypes != null && argTypes.length == 2) {
                    IdlJavaType keyJavaType = argTypes[0];
                    IdlJavaType valJavaType = argTypes[1];
                    boolean isMapKeySupport = isMapKeySupport(keyJavaType);
                    boolean isMapValSupport = isMapValSupport(valJavaType);
                    if (isMapKeySupport && isMapValSupport) {
                        protoType = "map<" + getSimpleName(keyJavaType.canonicalName()) + ", "
                                + getSimpleName(valJavaType.canonicalName()) + ">";
                    } else {
                        IdlFieldType fieldType = buildMapMsg(idlJavaType, parentInfo, buildOption, protoIdl,
                                serviceParser);
                        protoType = fieldType.getType();
                        if (!StringUtil.isEmpty(parentInfo.getMessageName())) {
                            Proto dtoProto = getDtoProtos(protoIdl).get(
                                    getPackageName(parentInfo.getMessageName()) + ".proto");

                            if (dtoProto != null) {
                                String protoPkgName = getProtoJavaPackageName(dtoProto);
                                if (protoPkgName.equals(getPackageName(parentInfo.getMessageName()))) {
                                    protoType = getSimpleName(protoType);
                                }
                            }
                        }
                        ift.setCardinality(Field.Cardinality.REPEATED);
                    }
                } else {
                    protoType = "";
                }
                break;
            default:   // 除了基本类型，判断是否是javabean，如果是javabean直接把JavaBean作为Proto的message
                if (clazzName.startsWith("java.")) {
                    protoType = "";
                } else {
                    protoType = clazzName;
                    serviceParser.buildBeanProto(clazzName, parentInfo.getServiceName(), buildOption, protoIdl);
                    impt = dtoPkgName;
                }
                break;

        }
        if (!StringUtil.isEmpty(impt)) {
            addServiceImport(parentInfo.getServiceName(), protoIdl, impt);
        }
        if (!StringUtil.isEmpty(parentInfo.getMessageName()) && !StringUtil.isEmpty(impt)) {
            addDtoImport(parentInfo.getMessageName(), protoIdl, impt);
        }
        ift.setType(protoType);
        return ift;
    }

    public static boolean isMapKeySupport(IdlJavaType keyJavaType) {
        boolean b;
        if (keyJavaType instanceof IdlJavaClass) {
            IdlJavaClass keyCls = (IdlJavaClass)keyJavaType;
            switch (keyCls.canonicalName()) {
                case "java.lang.String":
                case "int":
                case "java.lang.Integer":
                case "long":
                case "java.lang.Long":
                case "byte":
                case "java.lang.Byte":
                case "short":
                case "java.lang.Short":
                case "boolean":
                case "java.lang.Boolean":
                    b = true;
                    break;
                default:
                    b = false;
            }
        } else {
            b = false;
        }
        return b;
    }

    public static boolean isMapValSupport(IdlJavaType keyJavaType) {
        boolean b;
        if (keyJavaType instanceof IdlJavaClass) {
            IdlJavaClass keyCls = (IdlJavaClass)keyJavaType;
            switch (keyCls.canonicalName()) {
                case "java.util.Map":
                case "java.util.List":
                case "java.util.Set":
                case "java.lang.Iterator":
                    b = false;
                    break;
                default:
                    b = true;
            }

        } else {
            b = false;
        }
        return b;
    }


    public static String buildIdlResp(IdlJavaClass javaClass,
                                      String serviceName,
                                      IdlJavaType idlJavaType,
                                      BuildOption buildOption,
                                      ProtoIdl protoIdl,
                                      ServiceParser serviceParser) {
        StringBuilder resp = new StringBuilder();
        String name;
        if (idlJavaType != null) {
            name = baseTypeToProtoIdlType(idlJavaType, serviceName, buildOption, protoIdl, serviceParser);
        } else {
            name = baseTypeToProtoIdlType(javaClass, serviceName, buildOption, protoIdl, serviceParser);
        }
        if (StringUtil.isEmpty(name)) {
            MethodParam mp = new MethodParam();
            List<MethodParam> methodParams = new ArrayList<>();
            methodParams.add(mp);
            resp.append(buildCombineRespMessage(methodParams, serviceName, buildOption, protoIdl,
                    serviceParser));
        } else {
            resp.append(name);
        }
        return resp.toString();
    }

    /**
     * 多个参数或者函数入参为非POJO时，proto的函数入参将所有的参数组合为一个组合入参，该Message的包名为
     * "io.edap.protobuf.idl.combineparam",类名为"Combine_" + 参数个数 + "_" + "参数类型(包含泛型声明)用','
     * 拼接后做md5",message的field中添加option记录每个参数原始java类型
     */
    public static String buildCombineReqMessage(List<MethodParam> methodParams,
                                                String serviceName,
                                                BuildOption buildOption,
                                                ProtoIdl protoIdl,
                                                ServiceParser serviceParser) {
        Message msg = new Message();
        String name = "";
        ParentInfo parentInfo = new ParentInfo();
        parentInfo.setServiceName(serviceName);
        if (CollectionUtils.isEmpty(methodParams)) {
            return buildNoParam(serviceName, protoIdl, buildOption);
        } else {
            List<Field> fields = new ArrayList<>();
            int tag = 1;
            for (MethodParam mp : methodParams) {
                if (StringUtil.isEmpty(msg.getName())) {
                    name = mp.getIdlMethod().getName().substring(0, 1).toUpperCase(Locale.ENGLISH)
                            + mp.getIdlMethod().getName().substring(1) + "Req";
                    parentInfo.setMessageName(name);
                }
                IdlFieldType type;
                if (mp.getIdlJavaType() != null) {
                    type = toProtoIdlType(mp.getIdlJavaType(), parentInfo, buildOption,
                            protoIdl, serviceParser);
                } else {
                    type = toProtoIdlType(mp.getIdlJavaClass(), parentInfo, buildOption,
                            protoIdl, serviceParser);
                }
                Field field = new Field();
                if (StringUtil.isEmpty(mp.getParamName())) {
                    field.setName("param_" + tag);
                } else {
                    field.setName(mp.getParamName());
                }
                field.setTag(tag);
                field.setType(type.getType());
                List<Option> options = new ArrayList<>();
                options.add(new Option().setName("java_class")
                        .setValue(mp.getIdlJavaClass().canonicalName()));
                if (mp.getIdlJavaType() != null) {
                    options.add(new Option().setName("java_type")
                            .setValue(mp.getIdlJavaType().toString()));
                }
                field.setOptions(options);
                fields.add(field);
                tag++;
            }
            msg.setFields(fields);
        }
        Map<String, Proto> serviceProtos = protoIdl.getServiceProtos();
        String protoName = serviceName + ".proto";
        Proto serviceProto = serviceProtos.get(protoName);
        if (serviceProto == null) {
            throw new RuntimeException(protoName + " not found");
        }
        Message preMsg = serviceProto.getMessage(name);
        if (preMsg != null) {
            int i = 2;
            name += i;
            while (serviceProto.getMessage(name) != null) {
                i++;
                name += i;
            }
            msg.setName(name);
            serviceProto.addMsg(msg);
        } else {
            msg.setName(name);
            serviceProto.addMsg(msg);
        }
        return name;
    }

    public static String buildCombineRespMessage(List<MethodParam> methodParams,
                                                String serviceName,
                                                BuildOption buildOption,
                                                ProtoIdl protoIdl,
                                                ServiceParser serviceParser) {
        Message msg = new Message();
        String name = "";
        if (CollectionUtils.isEmpty(methodParams)) {
            return buildNoParam(serviceName, protoIdl, buildOption);
        } else {
            ParentInfo parentInfo = new ParentInfo();
            parentInfo.setServiceName(serviceName);
            List<Field> fields = new ArrayList<>();
            int tag = 1;
            for (MethodParam mp : methodParams) {
                if (StringUtil.isEmpty(msg.getName())) {
                    name = mp.getIdlMethod().getName().substring(0, 1).toUpperCase(Locale.ENGLISH)
                            + mp.getIdlMethod().getName().substring(1) + "Req";
                }
                IdlFieldType type;
                if (mp.getIdlJavaType() != null) {
                    type = toProtoIdlType(mp.getIdlJavaType(), parentInfo, buildOption,
                            protoIdl, serviceParser);
                } else {
                    type = toProtoIdlType(mp.getIdlJavaClass(), parentInfo, buildOption,
                            protoIdl, serviceParser);
                }
                Field field = new Field();
                if (StringUtil.isEmpty(mp.getParamName())) {
                    field.setName("param_" + tag);
                } else {
                    field.setName(mp.getParamName());
                }
                field.setTag(tag);
                field.setType(type.getType());
                List<Option> options = new ArrayList<>();
                options.add(new Option().setName("java_class")
                        .setValue(mp.getIdlJavaClass().canonicalName()));
                if (mp.getIdlJavaType() != null) {
                    options.add(new Option().setName("java_type")
                            .setValue(mp.getIdlJavaType().toString()));
                }
                field.setOptions(options);
                fields.add(field);
                tag++;
            }
            msg.setFields(fields);
        }
        Map<String, Proto> serviceProtos = protoIdl.getServiceProtos();
        String protoName = serviceName + ".proto";
        Proto serviceProto = serviceProtos.get(protoName);
        if (serviceProto == null) {
            throw new RuntimeException(protoName + " not found");
        }
        Message preMsg = serviceProto.getMessage(name);
        if (preMsg != null) {
            int i = 2;
            name += i;
            while (serviceProto.getMessage(name) != null) {
                i++;
                name += i;
            }
            msg.setName(name);
            serviceProto.addMsg(msg);
        } else {
            msg.setName(name);
            serviceProto.addMsg(msg);
        }
        return name;
    }

    public static void setProtoPackage(Proto proto, String packName) {
        proto.setProtoPackage(packName);
        List<Option> options = proto.getOptions();
        boolean needAdd = true;
        if (options == null) {
            options = new ArrayList<>();
            proto.setOptions(options);
        } else {
            for (Option option : options) {
                if (option.getName().equals("java_package")) {
                    option.setValue(packName);
                    needAdd = false;
                    break;
                }
            }
        }
        if (needAdd) {
            options.add(new Option().setName("java_package").setValue(packName));
        }
    }

    public static Map<String, Proto> getDtoProtos(ProtoIdl protoIdl) {
        Map<String, Proto> dtoProtos = protoIdl.getDtoProtos();
        if (dtoProtos == null) {
            dtoProtos = new HashMap<>();
            protoIdl.setDtoProtos(dtoProtos);
        }
        return dtoProtos;
    }

    public static void addDtoImport(String msgName, ProtoIdl protoIdl, String impt) {
        Map<String, Proto> dtoProtos = protoIdl.getDtoProtos();
        if (dtoProtos == null) {
            dtoProtos = new HashMap<>();
            protoIdl.setDtoProtos(dtoProtos);
        }
        String protoName = getPackageName(msgName) + ".proto";
        Proto dtoProto = dtoProtos.get(protoName);
        if (dtoProto != null) {
            if (getProtoJavaPackageName(dtoProto).equals(impt)) {
                return;
            }
            List<String> impts = dtoProto.getImports();
            if (impts == null) {
                impts = new ArrayList<>();
                dtoProto.setImports(impts);
            }
            if (!impts.contains(impt)) {
                impts.add(impt);
            }
        }
    }

    public static void addServiceImport(String serviceName, ProtoIdl protoIdl, String impt) {
        Map<String, Proto> serviceProtos = protoIdl.getServiceProtos();
        if (serviceProtos == null) {
            serviceProtos = new HashMap<>();
            protoIdl.setServiceProtos(serviceProtos);
        }
        String protoName = serviceName + ".proto";
        Proto serviceProto = serviceProtos.get(protoName);
        if (serviceProto != null) {
            List<String> impts = serviceProto.getImports();
            if (impts == null) {
                impts = new ArrayList<>();
                serviceProto.setImports(impts);
            }
            if (!impts.contains(impt)) {
                impts.add(impt);
            }
        }
    }

    public static void writeProtoIdl(String destPath, ProtoIdl protoIdl) throws IOException {
        writeProtoIdl(destPath, protoIdl, false);
    }

    public static void writeProtoIdl(String destPath, ProtoIdl protoIdl, boolean wellFormat) throws IOException {
        File dest = new File(destPath);
        if (!dest.exists()) {
            dest.mkdirs();
        }
        Map<String, Proto> serviceProtos = protoIdl.getServiceProtos();
        if (!CollectionUtils.isEmpty(serviceProtos)) {
            for (Map.Entry<String, Proto> entry : serviceProtos.entrySet()) {
                ProtoBuilder protobuilder = new ProtoV3Builder(entry.getValue());
                if (wellFormat) {
                    protobuilder.setCompactIdentation(false);
                }
                RandomAccessFile protoFile = new RandomAccessFile(dest + "/" + entry.getKey(),
                        "rw");
                protoFile.write(protobuilder.toProtoString().getBytes(StandardCharsets.UTF_8));
            }
        }
        if (!CollectionUtils.isEmpty(protoIdl.getDtoProtos())) {
            for (Map.Entry<String, Proto> entry : protoIdl.getDtoProtos().entrySet()) {
                ProtoBuilder protobuilder = new ProtoV3Builder(entry.getValue());
                if (wellFormat) {
                    protobuilder.setCompactIdentation(false);
                }
                RandomAccessFile protoFile = new RandomAccessFile(dest + "/" + entry.getKey(),
                        "rw");
                protoFile.write(protobuilder.toProtoString().getBytes(StandardCharsets.UTF_8));
            }
        }
    }

}
