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

package io.edap.protobuf.builder;

import io.edap.protobuf.annotation.ProtoField;
import io.edap.protobuf.internal.CodeBuilder;
import io.edap.protobuf.wire.*;
import io.edap.protobuf.wire.Field.Type;
import io.edap.protobuf.wire.Field.Cardinality;
import io.edap.protobuf.wire.ProtoEnum.EnumEntry;
import io.edap.protobuf.wire.WireFormat.JavaType;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.util.*;

public class JavaBuilder {
    private static final List<String> BASE_TYPES;
    private static final String EMPTY_STRING = "";

    private static final String LIST_IS_NULL_STR = "if ($name$ == null) {";
    private static final String LIST_NEW_STR = "$name$ = new ArrayList<>();";
    private static final String PACKNAME_STR = "package $package$;";

    static {
        BASE_TYPES = new ArrayList<>();
        String[] types = ("double,float,int32,int64,uint32,uint64,sint32,sint64,"
                + "fixed32,fixed64,sfixed32,sfixed64,bool,string,bytes").split(",");
        BASE_TYPES.addAll(Arrays.asList(types));
    }

    public static void saveJavaFile(String javaFilePath, String code)
            throws IOException {
        File f = new File(javaFilePath);
        if (f.exists()) {
            Files.delete(f.toPath());
        }
        try (RandomAccessFile java = new RandomAccessFile(javaFilePath, "rw")) {
            byte[] bs = code.getBytes("utf-8");
            java.write(bs);
        }
    }

    public static String packageToPath(String packName) {
        if (packName == null || packName.isEmpty()) {
            return "";
        }
        StringBuilder path = new StringBuilder();
        int start = 0;
        int index = packName.indexOf('.', start);
        String dir;
        while (start < packName.length()) {
            if (index == -1) {
                dir = packName.substring(start);
                path.append(File.separator).append(dir);
                break;
            } else {
                dir = packName.substring(start, index);
                path.append(File.separator).append(dir);
            }
            start = index + 1;
            index = packName.indexOf('.', start);
        }
        return path.toString();
    }

    private String getOptionJavaType(List<Option> options) {
        String jType = "";
        if (options != null && !options.isEmpty()) {
            for (Option option : options) {
                if ("java_type".equals(option.getName())) {
                    jType = option.getValue();
                }
            }
        }
        return jType;
    }

    public String getJavaType(Field field, List<String> imps, JavaBuildOption buildOps) {
        String type = field.getTypeString();
        if (field instanceof MapField) {
            MapField mf = (MapField)field;
            type = "Map<" + getJavaType(mf.getKey().value()) + ", "
                    + getJavaType(mf.getValue()) + ">";
            return type;
        }
        String jType = getOptionJavaType(field.getOptions());
        if (!BASE_TYPES.contains(type.toLowerCase(Locale.ENGLISH))) {
            return type;
        }
        JavaType javaType = null;
        javaType = Type.valueOf(type.toUpperCase(Locale.ENGLISH)).javaType();
        if (javaType != null && javaType.getTypeString() != null) {
            if (buildOps.isUseBoxed()) {
                type = javaType.getBoxedType();
            } else {
                String optionJavaType = getOptionJavaType(field.getOptions());
                if (optionJavaType == null || optionJavaType.isEmpty()) {
                    type = javaType.getTypeString();
                } else {
                    int lastDot = optionJavaType.lastIndexOf(".");
                    if (lastDot == -1) {
                        type = optionJavaType;
                    } else {
                        if (!imps.contains(optionJavaType)) {
                            imps.add(optionJavaType);
                        }
                        type = optionJavaType.substring(lastDot + 1);
                    }
                }

            }
        }
        if ("int64".equals(field.getType()) && jType != null) {
            switch (jType) {
                case "LocalDateTime":
                    addImport(imps, "java.time.LocalDateTime");
                    type = jType;
                    break;
                case "LocalDate":
                    type = jType;
                    addImport(imps, "java.time.LocalDate");
                    break;
                default:
                    break;
            }
        }
        return type;
    }

    private void addImport(List<String> imps, String imp) {
        if (imps == null) {
            return;
        }
        if (!imps.contains(imp)) {
            imps.add(imp);
        }
    }

    public String getJavaType(String type) {
        if (BASE_TYPES.contains(type.toLowerCase(Locale.ENGLISH))) {
            return Type.valueOf(type.toUpperCase(Locale.ENGLISH)).javaType().getTypeString();
        } else {
            return type;
        }
    }

    public String getAnnotationType(Field field, Proto proto, Map<String, ProtoEnum> protoEnums) {
        String type = field.getTypeString();
        if (BASE_TYPES.contains(type.toLowerCase(Locale.ENGLISH))) {
            return "Type." + Type.valueOf(type.toUpperCase(Locale.ENGLISH));
        } else {
            String name = getJavaPackage(proto) + "." + type;
            if (protoEnums.containsKey(name)) {
                return "Type." + Type.ENUM;
            } else {
                return "Type." + Type.MESSAGE;
            }
        }
    }

    private String fillDtoPackName(String packName, JavaBuildOption buildOps) {
        if (buildOps.getDtoPrefix() != null && !buildOps.getDtoPrefix().isEmpty()) {
            int index = packName.lastIndexOf('.');
            if (index != -1) {
                packName = packName.substring(0, index) + "."
                        + buildOps.getDtoPrefix() + packName.substring(index);
            } else {
                packName = buildOps.getDtoPrefix() + "." + packName;
            }

        }
        return packName;
    }

    private void buildDtoImps(CodeBuilder cb, List<ServiceMethod> methods, JavaBuildOption buildOps) {
        List<String> impMsgs = new ArrayList<>();
        if (buildOps.isEdapRpc()) {
            if (!impMsgs.contains("io.edap.rpc.annotation.EdapService")) {
                impMsgs.add("io.edap.rpc.annotation.EdapService");
            }
        }
        if (buildOps.getDtoPrefix() != null && !buildOps.getDtoPrefix().isEmpty()
                && methods != null && !methods.isEmpty()) {
            methods.stream()
                    .forEach(m -> {
                        String respName = fillDtoPackName(
                                buildOps.getJavaPackage() + "."
                                        + m.getResponse(), buildOps);
                        addImport(impMsgs, respName);
                        String reqName = fillDtoPackName(
                                buildOps.getJavaPackage() + "."
                                        + m.getRequest(), buildOps);
                        addImport(impMsgs, reqName);
                    });
        }
        if (methods != null && !methods.isEmpty()) {
            methods.stream()
                    .forEach(m -> {
                        switch (m.getType()) {
                            case CLIENT_STREAM:
                            case SERVER_STREAM:
                            case BIDIRECTIONAL:
                                addImport(impMsgs, "io.edap.rpc.StreamObserver");
                                break;
                        }
                    });
        }
        impMsgs.stream()
                .sorted(String::compareTo)
                .forEach(e -> cb.e("import $msg$;").arg(e).ln());
    }

    public String buildService(Service service, int indent, JavaBuildOption buildOps,
                               Map<String, String> msgComments, Proto proto) {
        int level = (indent < 1) ? 1 : indent;
        final CodeBuilder cb = new CodeBuilder();
        String javaPackage = buildOps.getJavaPackage();
        if (!buildOps.isIsNested() && javaPackage != null && !javaPackage.isEmpty()) {
            cb.e(PACKNAME_STR).arg(javaPackage).ln(2);
        }

        List<ServiceMethod> methods = service.getMethods();
        buildDtoImps(cb, methods, buildOps);
        String name;
        String serviceName = service.getName();
        if (serviceName == null || serviceName.length() < 1) {
            name = "";
        } else {
            name = serviceName.substring(0, 1).toUpperCase(Locale.ENGLISH)
                    + serviceName.substring(1);
        }
        cb.ln();
        cb.c("/**").ln();
        if (service.getComments() != null && !service.getComments().getLines().isEmpty()) {
            service.getComments().getLines().forEach(c -> cb.c(" * ").c(c).ln());
        }
        cb.c(" */").ln();
        if (buildOps.isEdapRpc()) {
            cb.e("@EdapService(proto = \"$protoName$\")").arg(proto.getFile().getName()).ln();
        }
        cb.e("public interface $name$ {").arg(name).ln();

        buildMethodCode(cb, methods, msgComments, level);
        cb.c("}").ln();
        return cb.toString();
    }

    private void buildMethodCode(CodeBuilder cb, List<ServiceMethod> methods, Map<String, String> msgComments, int level) {
        if (methods == null) {
            return;
        }
        methods.stream()
                .sorted(Comparator.comparing(ServiceMethod::getName))
                .forEach(m -> {
                    Comment comment = m.getComment();
                    if (comment.getType() == Comment.CommentType.DOCUMENT) {
                        cb.t(level).c("/**").ln();
                        for (String c : comment.getLines()) {
                            cb.t(level).c(" * ").c(c).ln();
                        }
                        cb.t(level).c(" */").ln();
                    } else if (comment.getType() == Comment.CommentType.MULTILINE) {
                        cb.t(level).c("/*").ln();
                        for (String c : comment.getLines()) {
                            cb.t(level).c(" * ").c(c).ln();
                        }
                        cb.t(level).c(" */").ln();
                    } else {
                        for (String c : comment.getLines()) {
                            cb.t(level).c(" * ").c(c).ln();
                        }
                    }

                    String[] params = new String[4];
                    params[0] = formatTypeName(m.getResponse(), m.getType());
                    params[1] = formatParamName(m.getName(), m.getType());
                    params[2] = formatTypeName(m.getRequest(), m.getType());
                    params[3] = formatParamName(m.getRequest(), m.getType());
                    cb.t(level).e(" * @param $param$ ")
                            .arg(params[3]).ln();
                    cb.t(level).e(" * @return").arg( params[3]).ln();
                    cb.t(level).c(" */").ln();
                    cb.t(level).e("$return$ $func$($param$ $arg$);")
                            .arg(params).ln();
                });
    }

    public static String formatParamName(String name, Service.ServiceType serviceType) {
        if (name == null || name.isEmpty()) {
            return "";
        }
        switch (serviceType) {
            case CLIENT_STREAM:
            case SERVER_STREAM:
            case BIDIRECTIONAL:
                int index = name.lastIndexOf(" ");
                if (index != -1) {
                    name = name.substring(index + 1);
                }
                break;
        }
        if (name.length() == 1) {
            return name.toLowerCase(Locale.ENGLISH);
        }
        return name.substring(0, 1).toLowerCase(Locale.ENGLISH) + name.substring(1);
    }

    public static String formatTypeName(String name, Service.ServiceType serviceType) {
        if (name == null || name.isEmpty()) {
            return "";
        }
        name = name.trim();
        boolean isStream = false;
        switch (serviceType) {
            case CLIENT_STREAM:
            case SERVER_STREAM:
            case BIDIRECTIONAL:
                int index = name.lastIndexOf(" ");
                if (index != -1) {
                    name = name.substring(index + 1);
                    isStream = true;
                }
                break;
        }
        String type;
        if (name.length() == 1) {
            type = name.toLowerCase(Locale.ENGLISH);
        } else {
            type = name.substring(0, 1).toUpperCase(Locale.ENGLISH) + name.substring(1);
        }
        if (isStream) {
            type = "StreamObserver<" + type + ">";
        }
        return type;
    }

    private void buildMsgImps(Message msg, List<Field> tmp, List<String> imps,
                              JavaBuildOption buildOps) {
        if (msg.getFields() != null) {
            msg.getFields().forEach(e -> {
                getJavaType(e, imps, buildOps);
                tmp.add(e);
            });
        }
        List<Oneof> oneofs = msg.getOneofs();
        if (oneofs != null) {
            oneofs.forEach(o -> {
                if (o.getFields() != null) {
                    o.getFields().forEach(tmp::add);
                }
            });
        }

        imps.add(ProtoField.class.getName());
        for (int i=0;i<tmp.size();i++) {
            if (tmp.get(i) instanceof MapField) {
                addImport(imps, "java.util.Map");
            } else if (tmp.get(i).getCardinality() == Field.Cardinality.REPEATED) {
                addImport(imps, "java.util.List");
                addImport(imps, "java.util.ArrayList");
            }
        }
    }

    private void buildDocComment(CodeBuilder cb, Comment comment, int level) {
        if (comment == null || comment.getLines() == null || comment.getLines().isEmpty()) {
            return;
        }
        cb.t(level).c("/**").ln();
        comment.getLines().forEach(c -> cb.t(level).c(" * ").c(c).ln());
        cb.t(level).c(" */").ln();
    }

    private Map<String, ProtoEnum> getAllProtoEnum(Proto proto, Map<String, Proto> protos) {
        Map<String, ProtoEnum> allEnums = new HashMap<>();
        List<ProtoEnum> protoEnums = proto.getEnums();
        if (protoEnums != null && !protoEnums.isEmpty()) {
            for (ProtoEnum protoEnum : protoEnums) {
                String packName = proto.getProtoPackage();
                String javaPack = getJavaPackage(proto);
                if (javaPack != null && javaPack.trim().length() > 0) {
                    packName = javaPack;
                }
                allEnums.put(packName + "." + protoEnum.getName(), protoEnum);
            }
        }
        List<String> impProtos = proto.getImports();
        if (impProtos == null || impProtos.isEmpty()) {
            return allEnums;
        }
        for (String protoName : impProtos) {
            Proto impProto = protos.get(protoName);
            if (impProto == null) {
                continue;
            }
            allEnums.putAll(getAllProtoEnum(impProto, protos));
        }
        return allEnums;
    }

    private String getJavaPackage(Proto proto) {
        if (proto.getOptions() == null || proto.getOptions().isEmpty()) {
            return EMPTY_STRING;
        }
        for (Option option : proto.getOptions()) {
            if ("java_package".equalsIgnoreCase(option.getName())) {
                return option.getValue();
            }
        }
        return EMPTY_STRING;
    }

    public String buildMessage(Proto proto, Message msg, int indent,
                               Map<String, String> defineMsgs, JavaBuildOption buildOps,
                               Map<String, Proto> protos) {
        Map<String, ProtoEnum> protoEnums = getAllProtoEnum(proto, protos);
        int level = (indent < 1) ? 1 : indent;
        final CodeBuilder cb = new CodeBuilder();
        String javaPackage = buildOps.getJavaPackage();
        List<Field> tmp = new ArrayList<>();
        List<String> imps = new ArrayList<>();
        buildMsgImps(msg, tmp, imps, buildOps);
        addImport(imps, Proto.class.getPackage().getName() + ".Field.Type");
        addImport(imps, "java.io.Serializable");

        List<Field> fields = new ArrayList<>();
        tmp.stream().sorted(Comparator.comparingInt(Field::getTag))
                .forEach(fields::add);

        if (!buildOps.isIsNested() && javaPackage != null && !javaPackage.isEmpty()) {
            cb.e(PACKNAME_STR).arg(buildOps.getJavaPackage()).ln(2);
        }
        if (!buildOps.isIsNested()) {
            imps.stream().sorted(Comparator.naturalOrder())
                    .forEach(e -> cb.t(level-1).e("import $cls$;").arg(e).ln());
        }
        cb.ln();
        buildDocComment(cb, msg.getComment(), level-1);
        cb.t(level-1).e("public class $name$ implements Serializable {").arg(msg.getName()).ln(2);

        CodeBuilder getCode;
        CodeBuilder setCode;

        int size = fields.size();

        List<String> getCodes = new ArrayList<>(size);
        List<String> setCodes = new ArrayList<>(size);

        CodeBuilder cons = new CodeBuilder();
        buildDefaultValCode(cons, msg, buildOps, level, protoEnums, proto);
        for (int i=0;i<size;i++) {
            Field f = fields.get(i);
            getCode = new CodeBuilder();
            setCode = new CodeBuilder();

            String typeName = getAnnotationType(f, proto, protoEnums);
            if (msg.getName().equals("Type")) {
                typeName = Proto.class.getPackage().getName() + ".wire.Field." + typeName;
            }
            buildDocComment(cb, f.getComment(), level);
            cb.t(level).e("@ProtoField(tag = $tag$, type = $type$)")
                    .arg(String.valueOf(f.getTag()), typeName).ln();
            String type = getJavaType(f, imps, buildOps);
            String name = f.getName();
            if (f.getCardinality() == Field.Cardinality.REPEATED) {
                String boxedTypeName = getBoxedTypeName(f, buildOps);
                type = "List<" + boxedTypeName + ">";
            }

            cb.t(level).e("private $type$ $name$;")
                    .arg(type, name).ln();

            String getMethod;
            if (!"bool".equalsIgnoreCase(f.getType())) {
                getMethod = "get" +
                        f.getName().substring(0, 1).toUpperCase(Locale.ENGLISH) +
                        f.getName().substring(1);
            } else {
                getMethod = "is" +
                        f.getName().substring(0, 1).toUpperCase(Locale.ENGLISH) +
                        f.getName().substring(1);
            }
            getCode.t(level).e("public $type$ $getMethod$() {")
                    .arg(type, getMethod).ln();
            getCode.t(level + 1).e("return $name$;").arg(f.getName()).ln();
            getCode.t(level).c("}").ln();
            getCodes.add(getCode.toString());

            String retType;
            if (buildOps.isChainOper()) {
                retType = msg.getName();
            } else {
                retType = "void";
            }
            String setMethod = "set" +
                    f.getName().substring(0, 1).toUpperCase(Locale.ENGLISH) +
                    f.getName().substring(1);
            setCode.t(level).e("public $retType$ $setMethod$($type$ $name$) {")
                    .arg(retType, setMethod, type, name).ln();
            setCode.t(level + 1).e("this.$name$ = $name$;")
                    .arg(f.getName(), f.getName()).ln();
            if (buildOps.isChainOper()) {
                setCode.t(level + 1).c("return this;").ln();
            }
            setCode.t(level).c("}").ln();
            setCodes.add(setCode.toString());
        }

        cb.ln().c(cons.toString());

        for (int i=0;i<size;i++) {
            cb.ln().c(getCodes.get(i));
            cb.ln().c(setCodes.get(i));
        }


        buildListCode(cb, fields, level, buildOps, imps);

        nestMessageMessage(proto, cb, msg.getMessages(), defineMsgs, level, protos);
        nestMessageEnum(cb, msg.getEnums(), level);

        cb.t(level-1).c("}");
        return cb.toString();
    }

    private String getBoxedTypeName(Field f, JavaBuildOption buildOps) {
        String type = getJavaType(f, null, buildOps);
        if (BASE_TYPES.contains(f.getTypeString())) {
            JavaType javaType = null;
            javaType = Type.valueOf(f.getTypeString().toUpperCase(Locale.ENGLISH)).javaType();
            if (javaType != null && javaType.getTypeString() != null) {
                type = javaType.getBoxedType();
            }
        }
        return type;
    }

    private void buildListCode(CodeBuilder cb, List<Field> fields, int level,
                               JavaBuildOption buildOps, List<String> imps) {
        CodeBuilder listCode = new CodeBuilder();
        for (int i=0;i<fields.size();i++) {
            Field f = fields.get(i);
            String setMethod = "set" +
                    f.getName().substring(0, 1).toUpperCase(Locale.ENGLISH) +
                    f.getName().substring(1);
            String type = getJavaType(f, imps, buildOps);
            String itemType = type;
            if (f.getCardinality() == Cardinality.REPEATED) {
                String boxedType = getBoxedTypeName(f, buildOps);
                type = "List<" + boxedType + ">";
            }
            if (f.getCardinality() == Cardinality.REPEATED) {
                String methodName = setMethod.substring(3);
                String itemName = "itemVar";
                itemType = getBoxedTypeName(f, buildOps);
                listCode.t(level).e("public void add$itemName$($itemType$ $itemTypeName$) {")
                        .arg(methodName, itemType, itemName).ln();
                listCode.t(level + 1).e(LIST_IS_NULL_STR).arg(f.getName()).ln();
                listCode.t(level + 2).e(LIST_NEW_STR).arg(f.getName()).ln();
                listCode.t(level + 1).e("}").ln();
                listCode.t(level + 1).e("$name$.add($itemName$);").arg(f.getName(), itemName).ln();
                listCode.t(level).c("}").ln();

                listCode.t(level).e("public void add$itemName$(int index, $itemType$ $itemTypeName$) {")
                        .arg(methodName, itemType, itemName).ln();
                listCode.t(level + 1).e(LIST_IS_NULL_STR).arg(f.getName()).ln();
                listCode.t(level + 2).e(LIST_NEW_STR).arg(f.getName()).ln();
                listCode.t(level + 1).e("}").ln();
                listCode.t(level + 1).e("$name$.add(index, $itemName$);").arg(f.getName(), itemName).ln();
                listCode.t(level).c("}").ln();

                listCode.t(level).e("public void add$itemName$($type$ $itemTypeName$) {")
                        .arg(methodName, type, itemName).ln();
                listCode.t(level + 1).e(LIST_IS_NULL_STR).arg(f.getName()).ln();
                listCode.t(level + 2).e(LIST_NEW_STR).arg(f.getName()).ln();
                listCode.t(level + 1).e("}").ln();
                listCode.t(level + 1).e("$name$.addAll($itemName$);").arg(f.getName(), itemName).ln();
                listCode.t(level).c("}").ln();

            }
        }
        cb.ln().c(listCode.toString());
    }

    private void buildDefaultValCode(CodeBuilder cons, Message msg, JavaBuildOption buildOps,
                                     int level, Map<String, ProtoEnum> protoEnums, Proto proto) {
        if (!buildOps.isHasDefaultValue()) {
            return;
        }
        cons.t(level).e("public $name$() {").arg(msg.getName()).ln();
        List<String> typeInt = Arrays.asList("int32","fixed32","uint32","sint32","sfixed32");
        List<String> typeLong = Arrays.asList("int64","fixed64","uint64","sint64","sfixed64");
        List<Field> fields = msg.getFields();
        for (int i=0;i<fields.size();i++) {
            Field f = fields.get(i);
            if (f.getCardinality() != Cardinality.REPEATED) {
                String type = f.getType();
                if (typeInt.contains(type) && buildOps.isUseBoxed()) {
                    cons.t(level + 1).e("this.$name$ = 0;")
                            .arg(f.getName()).ln();
                } else if (typeLong.contains(type) && buildOps.isUseBoxed()) {
                    cons.t(level + 1).e("this.$name$ = 0L;")
                            .arg(f.getName()).ln();
                } else if ("double".equalsIgnoreCase(type) && buildOps.isUseBoxed()) {
                    cons.t(level + 1).e("this.$name$ = 0D;")
                            .arg(f.getName()).ln();
                } else if ("float".equalsIgnoreCase(type) && buildOps.isUseBoxed()) {
                    cons.t(level + 1).e("this.$name$ = 0F;")
                            .arg(f.getName()).ln();
                } else if ("bool".equals(type) && buildOps.isUseBoxed()) {
                    cons.t(level + 1).e("this.$name$ = false;")
                            .arg(f.getName()).ln();
                } else if ("string".equals(type)) {
                    cons.t(level + 1).e("this.$name$ = \"\";")
                            .arg(f.getName()).ln();
                } else if ("bytes".equals(type)) {
                    cons.t(level + 1).e("this.$name$ = new byte[0];")
                            .arg(f.getName()).ln();
                } else {
                    String name = getJavaPackage(proto) + "." + type;
                    if (protoEnums.containsKey(name)) {
                        String enumDefVal = "";
                        ProtoEnum protoEnum = protoEnums.get(name);
                        for (EnumEntry entry : protoEnum.getEntries()) {
                            if (entry.getValue() == 0) {
                                enumDefVal = protoEnum.getName() + "." + entry.getLabel();
                            }
                        }
                        cons.t(level + 1).e("this.$name$ = $enum$;")
                                .arg(f.getName(), enumDefVal).ln();
                    }
                }
            }
        }
        cons.t(level).c("}").ln();
    }

    private void nestMessageMessage(Proto proto, CodeBuilder cb,
                                    List<Message> msgs, Map<String, String> defineMsgs,
                                    int level, Map<String, Proto> protos) {
        if (msgs == null || msgs.isEmpty()) {
            return;
        }
        cb.ln();
        JavaBuildOption nestedOps = new JavaBuildOption();
        nestedOps.setIsNested(true);
        msgs.stream().sorted(Comparator.comparing(Message::getName))
                .forEach(e -> cb.c(buildMessage(proto, e, level + 1, defineMsgs, nestedOps, protos)));
    }

    private void nestMessageEnum(CodeBuilder cb, List<ProtoEnum> enums, int indent) {
        if (enums == null || enums.isEmpty()) {
            return;
        }
        cb.ln();
        JavaBuildOption nestedOps = new JavaBuildOption();
        nestedOps.setIsNested(true);
        enums.stream()
                .sorted(Comparator.comparing(ProtoEnum::getName))
                .forEach(e -> cb.c(buildEnum(e, indent + 1, nestedOps)).ln(2));

    }

    private String getFillSpaces(int count) {
        if (count <= 0) {
            return EMPTY_STRING;
        }
        StringBuilder sb = new StringBuilder();
        for (int i=0;i<count;i++) {
            sb.append(" ");
        }
        return sb.toString();
    }

    public String buildEnum(ProtoEnum protoEnum, final int indent, JavaBuildOption buildOps) {
        int level = (indent < 1) ? 1 : indent;
        CodeBuilder cb = new CodeBuilder();
        String javaPackage = buildOps.getJavaPackage();
        if (!buildOps.isIsNested() && javaPackage != null && !javaPackage.isEmpty()) {
            cb.e(PACKNAME_STR).arg(buildOps.getJavaPackage()).ln(2);
        }

        cb.t(level-1).e("public enum $name$ {").arg(protoEnum.getName()).ln();
        List<EnumEntry> entries = protoEnum.getEntries();
        if (entries != null && !entries.isEmpty()) {
            Optional<EnumEntry> maxLen = entries.stream()
                    .max(Comparator.comparingInt(e -> e.getLabel().length()));
            int len = maxLen.isPresent()?maxLen.get().getLabel().length():1;

            List<EnumEntry> es = new ArrayList<>();
            entries.stream()
                    .sorted(Comparator.comparingInt(EnumEntry::getValue))
                    .forEach(es::add);
            CodeBuilder vsb = new CodeBuilder();
            CodeBuilder psb = new CodeBuilder();
            for (int i=0;i<es.size();i++) {
                EnumEntry o = es.get(i);
                String sep = (i != es.size()-1) ? "," : ";";
                String spaces = getFillSpaces(len - o.getLabel().length());
                String[] args = new String[]{o.getLabel(), spaces,
                        String.valueOf(o.getValue()), sep};
                cb.t(level).e("$label$$spaces$($value$)$;$").arg(args).ln();

                psb.t(level).e("public static final int $lable$_VALUE = $value$;")
                        .arg(o.getLabel(), String.valueOf(o.getValue())).ln();

                vsb.t(level+2).e("case $value$:").arg(String.valueOf(o.getValue())).ln();
                vsb.t(level+3).e("return $name$.$label$;")
                        .arg(protoEnum.getName(), o.getLabel()).ln();
            }
            cb.ln();

            cb.c(psb.toString()).ln();

            cb.t(level).c("private final int value;").ln(2);

            cb.t(level).e("private $label$(int value) {").arg(protoEnum.getName()).ln();
            cb.t(level+1).c("this.value = value;").ln();
            cb.t(level).c("}").ln();

            cb.t(level).c("public int getValue() {").ln();
            cb.t(level+1).c("return this.value;").ln();
            cb.t(level).c("}").ln(2);

            cb.t(level).e("public static $name$ valueOf(int value) {")
                    .arg(protoEnum.getName()).ln();
            cb.t(level+1).c("switch (value) {").ln();
            cb.c(vsb.toString());
            cb.t(level+2).c("default:").ln();
            cb.t(level+3).e("throw new IllegalArgumentException("
                            + "\"no enum value $value$ \" + value);")
                    .arg(protoEnum.getName()).ln();

            cb.t(level+1).c("}").ln();
            cb.t(level).c("}").ln();
        }
        cb.t(level-1).c("}");

        return cb.toString();
    }
}
