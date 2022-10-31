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

package io.edap.protobuf.rpc.util;

import io.edap.protobuf.ProtoBuf;
import io.edap.protobuf.model.MessageInfo;
import io.edap.protobuf.model.ProtoTypeInfo;
import io.edap.protobuf.util.ProtoTagComparator;
import io.edap.protobuf.util.ProtoUtil;
import io.edap.protobuf.wire.*;
import io.edap.util.CollectionUtils;
import io.edap.util.StringUtil;

import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static io.edap.protobuf.util.ProtoUtil.javaToProtoType;

/**
 * 使用Proto文件定义service的相关处理逻辑
 */
public class ProtoIdlUtil {

    private ProtoIdlUtil() {}

    /**
     * 根据给定的服务接口的类生成该接口对应的微服的proto描述对象列表
     * @param service 服务接口名
     * @return
     */
    public static List<Proto> buildServiceProto(Class service) throws IOException {
        List<Proto> protos = new ArrayList<>();
        if (service == null) {
            return protos;
        }

        // 生成proto文件的包名
        String packName = service.getPackage().getName();
        Proto proto = new Proto();
        proto.setName(service.getName());
        // 设置protocol buffer的版本
        proto.setSyntax(Syntax.PROTO_3);
        proto.setProtoPackage(packName);

        List<Option> options = new ArrayList<>();
        Option javaPackage = new Option().setName("java_package").setValue(packName);
        options.add(javaPackage);
        List<String> impts = new ArrayList<>();

        // 解析接口的方法，把方法生成proto文件的描述信息
        List<ServiceMethod> serviceMethods = new ArrayList<>();
        Method[] methods = service.getDeclaredMethods();
        for (Method method : methods) {
            parseMethodInfo(method, serviceMethods, proto);
        }

        proto.setOptions(options);
        proto.setImports(impts);
        protos.add(proto);
        return protos;
    }

    private static List<Proto> parseMethodInfo(Method method, List<ServiceMethod> serviceMethods, Proto proto) throws IOException {
        List<Proto> extensProtos = new ArrayList<>();
        Type[] paramTypes = method.getGenericParameterTypes();
        String request, response;
        ServiceMethod serviceMethod;
        if (paramTypes.length == 0) {
            request = "";
        } else {
            StringBuilder sb = new StringBuilder();
            for (Type type : paramTypes) {

            }
            request = sb.toString();
        }

        Type respType = method.getGenericReturnType();
        ProtoTypeInfo respProtoType = javaToProtoType(respType);
        response = "";
        MessageInfo msgInfo = respProtoType.getMessageInfo();
        if (msgInfo != null) {
            response = msgInfo.getMessageName();
            if (StringUtil.isEmpty(msgInfo.getImpFile())) {
                List<String> imps = proto.getImports();
                if (imps == null) {
                    imps = new ArrayList<>();
                    imps.add(msgInfo.getImpFile());
                    proto.setImports(imps);
                } else {
                    if (!imps.contains(msgInfo.getImpFile())) {
                        imps.add(msgInfo.getImpFile());
                    }
                }
            }
        } else {
            response = respProtoType.getProtoType().name();
            List<Proto> extProtos = buildClassMessage(getProtoMessageClass(respType), proto);
            if (!CollectionUtils.isEmpty(extProtos)) {
                extensProtos.addAll(extProtos);
            }
        }
        serviceMethod = new ServiceMethod()
                .setName(method.getName())
                .setType(Service.ServiceType.UNARY)
                .setRequest(request)
                .setResponse(response);
        serviceMethods.add(serviceMethod);

        return extensProtos;
    }

    /**
     * 根据给定的javaBean的class生成改Class对应的Message添加到Proto的对象中，如果该javaBean中有其他的Message如果有其他包名的则生成新的
     * 的Proto对象列表返回
     * @param msgCls
     * @param proto
     * @return
     */
    public static List<Proto> buildClassMessage(Class msgCls, Proto proto) throws IOException {
        List<Proto> protos = new ArrayList<>();
        Message msg = new Message();
        String pkgName = msgCls.getPackage().getName();
        String protoPkgName = getProtoJavaPackageName(proto);
        Proto curProto = proto;
        if (pkgName.equals(protoPkgName)) {
            curProto = new Proto();
            curProto.setProtoPackage(pkgName);
            curProto.addOption(new Option().setName("java_package").setValue(pkgName));
            protos.add(proto);
        }
        curProto.addMsg(msg);
        List<ProtoBuf.ProtoFieldInfo> protoFieldInfos = ProtoUtil.getProtoFields(msgCls);
        if (CollectionUtils.isEmpty(protoFieldInfos)) {
            return protos;
        }
        ProtoTagComparator ptc = new ProtoTagComparator();
        Collections.sort(protoFieldInfos, ptc);
        for (ProtoBuf.ProtoFieldInfo fieldInfo : protoFieldInfos) {
            Field field = new Field();
            field.setName(fieldInfo.field.getName());
            field.setTag(fieldInfo.protoField.tag());
            field.setType(fieldInfo.protoField.type().name());
            field.setCardinality(fieldInfo.protoField.cardinality());
            field.setOptions(parseOptions(fieldInfo.protoField.options()));
            msg.addField(field);
        }
        return protos;
    }

    public static Class getProtoMessageClass(Type type) {
        if (type instanceof Class) {
            return (Class)type;
        }
        return null;
    }

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
