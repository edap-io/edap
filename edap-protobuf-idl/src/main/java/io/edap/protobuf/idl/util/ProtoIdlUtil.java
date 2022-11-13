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
import io.edap.protobuf.model.MessageInfo;
import io.edap.protobuf.model.ProtoTypeInfo;
import io.edap.protobuf.idl.BuildOption;
import io.edap.protobuf.idl.UnsupportDataType;
import io.edap.protobuf.util.ProtoTagComparator;
import io.edap.protobuf.util.ProtoUtil;
import io.edap.protobuf.wire.*;
import io.edap.util.CollectionUtils;
import io.edap.util.StringUtil;

import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.*;

import static io.edap.protobuf.util.ProtoUtil.javaToProtoType;

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
}
