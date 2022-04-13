/*
 * Copyright 2020 The edap Project
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

package io.edap.protobuf;

import io.edap.protobuf.annotation.ProtoField;
import io.edap.protobuf.internal.GeneratorClassInfo;
import io.edap.protobuf.ProtoBuf.ProtoFieldInfo;
import io.edap.protobuf.util.ProtoUtil;
import io.edap.protobuf.wire.Field;
import io.edap.protobuf.wire.Field.Type;
import io.edap.protobuf.wire.WireFormat;
import io.edap.util.*;
import org.objectweb.asm.*;

import java.io.IOException;
import java.lang.reflect.ParameterizedType;
import java.util.*;

import static io.edap.protobuf.ProtoBufEncoderGenerator.CLAZZ_UTIL_NAME;
import static io.edap.protobuf.util.ProtoAsmUtil.visitGetFieldValue;
import static io.edap.protobuf.util.ProtoUtil.*;
import static io.edap.util.AsmUtil.*;
import static io.edap.util.ClazzUtil.getDescriptor;
import static io.edap.util.ClazzUtil.getTypeName;
import static org.objectweb.asm.Opcodes.*;
import static org.objectweb.asm.Opcodes.RETURN;

public class ProtoBufDecoderGenerator {

    static final String IFACE_NAME = toInternalName(ProtoBufDecoder.class.getName());
    static final String REGISTER_NAME = toInternalName(ProtoBufCodecRegister.class.getName());
    static final String READER_NAME = toInternalName(ProtoBufReader.class.getName());
    static final String COLLECTION_UTIL = toInternalName(CollectionUtils.class.getName());
    static final String FIELD_TYPE_NAME = toInternalName(Field.Type.class.getName());
    static final String CLAZZ_UTIL_NAME = toInternalName(ClazzUtil.class.getName());

    private ClassWriter cw;
    private final Class pojoCls;
    private final ProtoBuf.EncodeType encodeType;

    private List<GeneratorClassInfo> inners;
    private String parentName;
    private String pojoName;
    private String pojoCodecName;

    private final List<ProtoBuf.ProtoFieldInfo> arrayFields = new ArrayList<>();
    private final List<ProtoBuf.ProtoFieldInfo> listFields = new ArrayList<>();
    private final List<ProtoBuf.ProtoFieldInfo> mapFields = new ArrayList<>();
    private final List<ProtoBuf.ProtoFieldInfo> stringFields = new ArrayList<>();

    private final List<String> listMethods = new ArrayList<>();
    private final List<String> arrayMethods = new ArrayList<>();
    private final List<String> mapMethods = new ArrayList<>();

    private final java.lang.reflect.Type parentMapType;

    public ProtoBufDecoderGenerator(Class pojoCls, ProtoBuf.EncodeType encodeType) {
        this.pojoCls = pojoCls;
        this.encodeType = encodeType;
        this.parentMapType = ProtoUtil.parentMapType(pojoCls);
    }

    public GeneratorClassInfo getClassInfo() throws IOException {
        GeneratorClassInfo gci = new GeneratorClassInfo();
        inners = new ArrayList<>();
        pojoName = toInternalName(pojoCls.getName());
        parentName = toInternalName(AbstractDecoder.class.getName());
        pojoCodecName = toInternalName(getDecoderName(pojoCls, encodeType));
        gci.clazzName = pojoCodecName;
        String pojoCodecDescriptor = getDecoderDescriptor(pojoCls);
        String[] ifaceName = new String[]{IFACE_NAME};

        //定义编码器名称，继承的虚拟编码器以及实现的接口
        cw = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        cw.visit(V1_8, ACC_PUBLIC + ACC_SUPER, pojoCodecName,
                pojoCodecDescriptor, parentName, ifaceName);

        List<ProtoFieldInfo> fields = ProtoUtil.getProtoFields(pojoCls);
        List<java.lang.reflect.Type> pojoTypes = new ArrayList<>();
        for (ProtoFieldInfo pfi : fields) {
            if (pfi.field.getType().getName().equals("java.lang.String")) {
                stringFields.add(pfi);
            }
            if (isPojo(pfi.field.getGenericType())
                    && !pojoTypes.contains(pfi.field.getGenericType())) {
                pojoTypes.add(pfi.field.getGenericType());
            } else if (isArray(pfi.field.getGenericType()) && !isPacked(pfi)) {

                java.lang.reflect.Type ctype = pfi.field.getGenericType();
                if (ctype instanceof Class) {
                    Class cCls = (Class)ctype;
                    Class itemClass = cCls.getComponentType();
                    if (!itemClass.isPrimitive()) {
                        arrayFields.add(pfi);
                    }
                    if (isPojo(cCls.getComponentType())) {
                        pojoTypes.add(cCls.getComponentType());
                    }
                } else {
                    arrayFields.add(pfi);
                }

            } else if (pfi.field.getGenericType() instanceof ParameterizedType) {
                List<java.lang.reflect.Type> itemTypes =
                        getAllPojoTypes(pfi.field.getGenericType());
                for (java.lang.reflect.Type t : itemTypes) {
                    if (!pojoTypes.contains(t)) {
                        pojoTypes.add(t);
                    }
                }
                if (isList(pfi.field.getGenericType()) && !isPacked(pfi)) {
                    listFields.add(pfi);
                } else if (isMap(pfi.field.getGenericType())) {
                    mapFields.add(pfi);
                }
            } else {
                if (isMap(pfi.field.getGenericType())) {
                    mapFields.add(pfi);
                }
            }
        }
        visitClinitMethod();
        visitInitMethod(pojoTypes, fields);
        visitDecodeMethod(fields);

        visitDecodeBridgeMethod();

        cw.visitEnd();
        gci.inners = inners;
        gci.clazzBytes = cw.toByteArray();

        return gci;
    }

    private void visitDecodeMethod(List<ProtoFieldInfo> fields) {
        MethodVisitor mv;
        String exName = toInternalName(ProtoBufException.class.getName());

        mv = cw.visitMethod(ACC_PUBLIC, "decode",
                "(L" + READER_NAME + ";)L" + pojoName + ";", null,
                new String[] { exName });
        mv.visitCode();
        int varReader = 1;
        int varPojo = 2;
        mv.visitTypeInsn(NEW, pojoName);
        mv.visitInsn(DUP);
        visitMethod(mv, INVOKESPECIAL, pojoName, "<init>", "()V", false);
        mv.visitVarInsn(ASTORE, varPojo);

        List<Object> framObjs = new ArrayList<>();
        framObjs.add(pojoName);

        int preNextVar = 2;
        Map<Integer, Integer> aArrayTag = new HashMap<>();
        for (int i=0;i<arrayFields.size();i++) {
            ProtoFieldInfo pfi = arrayFields.get(i);
            String fname = "ARR_LIST_" + i;
            mv.visitFieldInsn(GETSTATIC, pojoCodecName, fname,
                    "Ljava/lang/ThreadLocal;");
            visitMethod(mv, INVOKEVIRTUAL, "java/lang/ThreadLocal", "get",
                    "()Ljava/lang/Object;", false);
            mv.visitTypeInsn(CHECKCAST, "java/util/List");
            preNextVar++;
            int varIndex = preNextVar;
            mv.visitVarInsn(ASTORE, varIndex);
            mv.visitVarInsn(ALOAD, varIndex);
            visitMethod(mv, INVOKEINTERFACE, "java/util/List", "clear",
                    "()V", true);
            aArrayTag.put(pfi.protoField.tag(), varIndex);
            framObjs.add("java/util/List");
        }
        preNextVar += arrayFields.size();
        Map<Integer, Integer> aListTag = new HashMap<>();
        for (int i=0;i<listFields.size();i++) {
            ProtoFieldInfo pfi = listFields.get(i);
            mv.visitTypeInsn(NEW, "java/util/ArrayList");
            mv.visitInsn(DUP);
            mv.visitIntInsn(BIPUSH, 16);
            visitMethod(mv, INVOKESPECIAL, "java/util/ArrayList", "<init>",
                    "(I)V", false);
            preNextVar++;
            int varIndex = preNextVar;
            mv.visitVarInsn(ASTORE, varIndex);
            aListTag.put(pfi.protoField.tag(), varIndex);
            framObjs.add("java/util/List");
        }
        preNextVar += listFields.size();
        Map<Integer, Integer> aMapTag = new HashMap<>();
        for (int i=0;i<mapFields.size();i++) {
            ProtoFieldInfo pfi = mapFields.get(i);
            mv.visitTypeInsn(NEW, "java/util/HashMap");
            mv.visitInsn(DUP);
            visitMethod(mv, INVOKESPECIAL, "java/util/HashMap", "<init>",
                    "()V", false);
            preNextVar++;
            int varIndex = preNextVar;
            mv.visitVarInsn(ASTORE, varIndex);
            aMapTag.put(pfi.protoField.tag(), varIndex);
            framObjs.add("java/util/Map");
        }

        framObjs.add("java/lang/Integer");

        //判断是否读取到末尾
        preNextVar++;
        int varFinished = preNextVar;
        mv.visitInsn(ICONST_0);
        mv.visitVarInsn(ISTORE, varFinished);
        Label l0 = new Label();
        mv.visitLabel(l0);
        mv.visitFrame(Opcodes.F_APPEND, framObjs.size(), framObjs.toArray(), 0, null);
        mv.visitVarInsn(ILOAD, varFinished);


        int varTag = varFinished + 1;
        Label l1 = new Label();
        mv.visitJumpInsn(IFNE, l1);
        mv.visitVarInsn(ALOAD, varReader);
        visitMethod(mv, INVOKEINTERFACE, READER_NAME, "readTag", "()I", true);
        mv.visitVarInsn(ISTORE, varTag);
        mv.visitVarInsn(ILOAD, varTag);



        //switch tag逻辑
        int fieldCount = fields.size();
        int[] tagArray;
        Label[] labels;
        if (parentMapType != null) {
            tagArray = new int[fieldCount + 2];
            labels   = new Label[fieldCount + 2];
        } else {
            tagArray = new int[fieldCount + 1];
            labels   = new Label[fieldCount + 1];
        }

        List<ProtoFieldInfo> sortFields = new ArrayList<>();
        sortFields.addAll(fields);
        Collections.sort(sortFields, new Comparator<ProtoFieldInfo>() {
            @Override
            public int compare(ProtoFieldInfo o1, ProtoFieldInfo o2) {
                if (o1.protoField.tag() > o2.protoField.tag()) {
                    return 1;
                } else if (o1.protoField.tag() == o2.protoField.tag()) {
                    return 0;
                } else {
                    return -1;
                }
            }
        });

        tagArray[0] = 0;
        labels[0] = new Label();
        for (int i = 0; i < fieldCount; i++) {
            ProtoField pfi = sortFields.get(i).protoField;
            tagArray[i+1] = buildFieldValue(pfi.tag(), pfi.type(), pfi.cardinality());
            //tagArray[i] = pfi.tag();
            labels[i+1] = new Label();
        }
        if (parentMapType != null) {
            tagArray[fieldCount+1] = buildFieldValue(WireFormat.RESERVED_TAG_VALUE_START, Type.MAP, Field.Cardinality.OPTIONAL);
            labels[fieldCount+1] = new Label();
        }

        Label dfltLabel = new Label();
        mv.visitLookupSwitchInsn(dfltLabel, tagArray, labels);

        int varSwitchPre = preNextVar;
        //如果tag为0则跳出switch分支
        mv.visitLabel(labels[0]);
        mv.visitFrame(Opcodes.F_APPEND,1, new Object[] {Opcodes.INTEGER}, 0, null);
        mv.visitInsn(ICONST_1);
        mv.visitVarInsn(ISTORE, varFinished);
        Label finishLabel = new Label();
        mv.visitJumpInsn(GOTO, finishLabel);

        for (int i = 0; i < fieldCount; i++) {
            ProtoFieldInfo pfi = sortFields.get(i);
            mv.visitLabel(labels[i+1]);
            mv.visitFrame(F_SAME, 0, null, 0, null);
            ReadMethodInfo readMethod = getProtoReadMethod(pfi);

            if (isArray(pfi.field.getGenericType())) {
                java.lang.reflect.Type itemType = pfi.field.getType().getComponentType();
                boolean isPrimitive = isPrimitive(pfi);
                if (isPojo(itemType)) {
                    String itemTypeName = toInternalName(getTypeName(itemType));
                    String pname = getPojoDecoderName(itemType);

                    mv.visitVarInsn(ALOAD, aArrayTag.get(pfi.protoField.tag()));
                    mv.visitVarInsn(ALOAD, 1);
                    mv.visitVarInsn(ALOAD, 0);
                    String decodeName = getDecoderName((Class)itemType, encodeType);
                    if (!toInternalName(decodeName).equals(pojoCodecName)) {
                        mv.visitFieldInsn(GETFIELD, pojoCodecName, pname,
                                "L" + IFACE_NAME + ";");
                    }
                    visitMethod(mv, INVOKEINTERFACE, READER_NAME, "readMessage",
                            "(L" + IFACE_NAME + ";)Ljava/lang/Object;", true);
                    mv.visitTypeInsn(CHECKCAST, itemTypeName);
                    visitMethod(mv, INVOKEINTERFACE, "java/util/List", "add",
                            "(Ljava/lang/Object;)Z", true);
                    mv.visitInsn(POP);
                } else if (pfi.protoField.type() == Type.BOOL) {
                    String itemDesc = getDescriptor(itemType);
                    String readMethodName = "readPackedBools";
                    if (!"Ljava/lang/Boolean;".equals(itemDesc)) {
                        readMethodName = "readPackedBoolValues";
                    }
                    if (!pfi.hasSetAccessed) {
                        mv.visitVarInsn(ALOAD, 0);
                        mv.visitFieldInsn(GETFIELD, pojoCodecName, pfi.field.getName() + "F", "Ljava/lang/reflect/Field;");
                    }
                    mv.visitVarInsn(ALOAD, 2);
                    mv.visitVarInsn(ALOAD, 1);
                    mv.visitMethodInsn(INVOKEINTERFACE, READER_NAME, readMethodName, "()[" + itemDesc, true);
                    visitSetValueOpcode(mv, pfi);
                } else if (pfi.protoField.type() == Type.DOUBLE) {
                    if (!pfi.hasSetAccessed) {
                        mv.visitVarInsn(ALOAD, 0);
                        mv.visitFieldInsn(GETFIELD, pojoCodecName, pfi.field.getName() + "F", "Ljava/lang/reflect/Field;");
                    }
                    mv.visitVarInsn(ALOAD, 2);
                    mv.visitVarInsn(ALOAD, 1);
                    mv.visitMethodInsn(INVOKEINTERFACE, READER_NAME, readMethod.method, "()" + readMethod.returnType, true);
                    visitSetValueOpcode(mv, pfi);
                } else if (pfi.protoField.type() == Type.ENUM) {
                    String name = visitConvertEnumArrayMethod(pfi);
                    if (!pfi.hasSetAccessed) {
                        mv.visitVarInsn(ALOAD, 0);
                        mv.visitFieldInsn(GETFIELD, pojoCodecName, pfi.field.getName() + "F", "Ljava/lang/reflect/Field;");
                    }
                    mv.visitVarInsn(ALOAD, 2);
                    mv.visitVarInsn(ALOAD, 0);
                    mv.visitVarInsn(ALOAD, 1);
                    mv.visitFieldInsn(GETSTATIC, FIELD_TYPE_NAME, "INT32", "L" + FIELD_TYPE_NAME + ";");
                    visitMethod(mv, INVOKEINTERFACE, READER_NAME, "readPackedInt32ArrayValue", "(L" + FIELD_TYPE_NAME + ";)[I", true);
                    visitMethod(mv, INVOKESPECIAL, pojoCodecName, name, "([I)[" + getDescriptor(itemType), false);
                    visitSetValueOpcode(mv, pfi);
                } else if (isPrimitive) {
                    if (!pfi.hasSetAccessed) {
                        mv.visitVarInsn(ALOAD, 0);
                        mv.visitFieldInsn(GETFIELD, pojoCodecName, pfi.field.getName() + "F", "Ljava/lang/reflect/Field;");
                    }
                    mv.visitVarInsn(ALOAD, varPojo);
                    mv.visitVarInsn(ALOAD, 1);
                    if (pfi.protoField.type() == Type.INT32
                            || pfi.protoField.type() == Type.FIXED32
                            || pfi.protoField.type() == Type.FIXED64
                            || pfi.protoField.type() == Type.INT64
                            || pfi.protoField.type() == Type.SINT32
                            || pfi.protoField.type() == Type.SINT64
                            || pfi.protoField.type() == Type.UINT32
                            || pfi.protoField.type() == Type.UINT64
                            || pfi.protoField.type() == Type.SFIXED32
                            || pfi.protoField.type() == Type.SFIXED64) {
                        mv.visitFieldInsn(GETSTATIC, FIELD_TYPE_NAME,
                                pfi.protoField.type().name(),
                                "L" + FIELD_TYPE_NAME + ";");
                    }
                    visitMethod(mv, INVOKEINTERFACE, READER_NAME, readMethod.method,
                            "(" + readMethod.paramType + ")" + readMethod.returnType, true);
                    visitSetValueOpcode(mv, pfi);
                } else {
                    mv.visitVarInsn(ALOAD, aArrayTag.get(pfi.protoField.tag()));
                    mv.visitVarInsn(ALOAD, 1);
                    visitMethod(mv, INVOKEINTERFACE, READER_NAME, readMethod.method,
                            "()" + readMethod.returnType, true);
                    visitMethod(mv, INVOKEINTERFACE, "java/util/List", "add",
                            "(Ljava/lang/Object;)Z", true);
                    mv.visitInsn(POP);
                }
            } else if (isEnum(pfi.field.getGenericType())) {
                if (!pfi.hasSetAccessed) {
                    mv.visitVarInsn(ALOAD, 0);
                    mv.visitFieldInsn(GETFIELD, pojoCodecName, pfi.field.getName() + "F", "Ljava/lang/reflect/Field;");
                }
                String enumName = toInternalName(getTypeName(pfi.field.getType()));
                mv.visitVarInsn(ALOAD, 2);
                if (!implInterface(pfi.field.getType(), ProtoBufEnum.class)) {
                    visitMethod(mv, INVOKESTATIC, enumName, "values",
                            "()[L" + enumName + ";", false);
                    mv.visitVarInsn(ALOAD, 1);
                    visitMethod(mv, INVOKEINTERFACE, READER_NAME, readMethod.method,
                            "()" + readMethod.returnType, true);
                    mv.visitInsn(AALOAD);
                } else {
                    mv.visitVarInsn(ALOAD, 1);
                    visitMethod(mv, INVOKEINTERFACE, READER_NAME, "readInt32", "()I", true);
                    visitMethod(mv, INVOKESTATIC, enumName, "valueOf",
                            "(I)L" + enumName + ";", false);
                }
                visitSetValueOpcode(mv, pfi);
                if (pfi.setMethod != null
                        && !"V".equals(getDescriptor(pfi.setMethod.getGenericReturnType()))) {
                    mv.visitInsn(POP);
                }
            } else if (isMap(pfi.field.getGenericType())) {
                String keyTypeDesc = "Ljava/lang/Object;";
                String valTypeDesc = "Ljava/lang/Object;";
                if (pfi.field.getGenericType() instanceof ParameterizedType) {
                    ParameterizedType ptype = (ParameterizedType) pfi.field.getGenericType();
                    keyTypeDesc = getDescriptor(ptype.getActualTypeArguments()[0]);
                    valTypeDesc = getDescriptor(ptype.getActualTypeArguments()[1]);
                }
                Class mapEntryCls = ProtoBufCodecRegister.INSTANCE
                        .generateMapEntryClass(pfi.field.getGenericType());
                String codecName = getMapCodecName(mapEntryCls);
                String mapTypeName = toInternalName(mapEntryCls.getName());

                mv.visitVarInsn(ALOAD, 1);
                mv.visitVarInsn(ALOAD, 0);
                mv.visitFieldInsn(GETFIELD, pojoCodecName, codecName,
                        "L" + IFACE_NAME + ";");
                visitMethod(mv, INVOKEINTERFACE, READER_NAME, "readMessage",
                        "(L" + IFACE_NAME + ";)Ljava/lang/Object;", true);
                mv.visitTypeInsn(CHECKCAST, mapTypeName);
                varSwitchPre++;
                mv.visitVarInsn(ASTORE, varSwitchPre);
                mv.visitVarInsn(ALOAD, aMapTag.get(pfi.protoField.tag()));
                mv.visitVarInsn(ALOAD, varSwitchPre);
                mv.visitFieldInsn(GETFIELD, mapTypeName, "key", keyTypeDesc);
                mv.visitVarInsn(ALOAD, varSwitchPre);
                mv.visitFieldInsn(GETFIELD, mapTypeName, "value", valTypeDesc);
                visitMethod(mv, INVOKEINTERFACE, "java/util/Map", "put",
                        "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;",
                        true);
                mv.visitInsn(POP);

            } else if (isList(pfi.field.getGenericType())) {

                ParameterizedType ptype = (ParameterizedType)pfi.field.getGenericType();
                java.lang.reflect.Type itemType = ptype.getActualTypeArguments()[0];

                if (isPojo(itemType)) {
                    String itemTypeName = toInternalName(getTypeName(itemType));
                    String pname = getPojoDecoderName(itemType);
                    mv.visitVarInsn(ALOAD, aListTag.get(pfi.protoField.tag()));
                    mv.visitVarInsn(ALOAD, 1);
                    mv.visitVarInsn(ALOAD, 0);
                    String decodeName = getDecoderName((Class)itemType, encodeType);
                    if (!toInternalName(decodeName).equals(pojoCodecName)) {
                        mv.visitFieldInsn(GETFIELD, pojoCodecName, pname,
                                "L" + IFACE_NAME + ";");
                    }
                    visitMethod(mv, INVOKEINTERFACE, READER_NAME, "readMessage",
                            "(L" + IFACE_NAME + ";)Ljava/lang/Object;", true);
                    mv.visitTypeInsn(CHECKCAST, itemTypeName);
                    visitMethod(mv, INVOKEINTERFACE, "java/util/List", "add",
                            "(Ljava/lang/Object;)Z", true);
                    mv.visitInsn(POP);
                } else if (isEnum(itemType)) {
                    String convertName = visitConvertEnumMethod(pfi);
                    if (!pfi.hasSetAccessed) {
                        mv.visitVarInsn(ALOAD, 0);
                        mv.visitFieldInsn(GETFIELD, pojoCodecName, pfi.field.getName() + "F", "Ljava/lang/reflect/Field;");
                    }
                    mv.visitVarInsn(ALOAD, 2);
                    mv.visitVarInsn(ALOAD, 0);
                    mv.visitVarInsn(ALOAD, 1);
                    mv.visitFieldInsn(GETSTATIC, FIELD_TYPE_NAME, "INT32", "L" + FIELD_TYPE_NAME + ";");
                    mv.visitMethodInsn(INVOKEINTERFACE, READER_NAME, "readPackedInt32", "(L" + FIELD_TYPE_NAME+ ";)Ljava/util/List;", true);
                    mv.visitMethodInsn(INVOKESPECIAL, pojoCodecName, convertName, "(Ljava/util/List;)Ljava/util/List;", false);
                    visitSetValueOpcode(mv, pfi);
                    if (pfi.setMethod != null
                            && !"V".equals(getDescriptor(pfi.setMethod.getGenericReturnType()))) {
                        mv.visitInsn(POP);
                    }
                } else if (isPacked(pfi)) {
                    if (!pfi.hasSetAccessed) {
                        mv.visitVarInsn(ALOAD, 0);
                        mv.visitFieldInsn(GETFIELD, pojoCodecName, pfi.field.getName() + "F", "Ljava/lang/reflect/Field;");
                    }
                    mv.visitVarInsn(ALOAD, 2);
                    mv.visitVarInsn(ALOAD, 1);
                    if (!StringUtil.isEmpty(readMethod.paramType)) {
                        mv.visitFieldInsn(GETSTATIC, FIELD_TYPE_NAME,
                                pfi.protoField.type().name(),
                                "L" + FIELD_TYPE_NAME + ";");
                        visitMethod(mv, INVOKEINTERFACE, READER_NAME, readMethod.method,
                                "(" + readMethod.paramType + ")" + readMethod.returnType, true);
                    } else {
                        visitMethod(mv, INVOKEINTERFACE, READER_NAME, readMethod.method,
                                "()" + readMethod.returnType, true);
                    }
                    visitSetValueOpcode(mv, pfi);
                    if (pfi.setMethod != null
                            && !"V".equals(getDescriptor(pfi.setMethod.getGenericReturnType()))) {
                        mv.visitInsn(POP);
                    }
                } else {

                    mv.visitVarInsn(ALOAD, aListTag.get(pfi.protoField.tag()));
                    mv.visitVarInsn(ALOAD, 1);
                    visitMethod(mv, INVOKEINTERFACE, READER_NAME, readMethod.method,
                            "()" + readMethod.returnType, true);
                    visitBoxOpcode(mv, pfi, itemType);
                    visitMethod(mv, INVOKEINTERFACE, "java/util/List", "add",
                            "(Ljava/lang/Object;)Z", true);
                    mv.visitInsn(POP);
                }
            } else if (isPojo(pfi.field.getGenericType())) {

                String pname = getPojoDecoderName(pfi.field.getType());
                String pojo = toInternalName(pfi.field.getType().getName());
                if (!pfi.hasSetAccessed) {
                    mv.visitVarInsn(ALOAD, 0);
                    mv.visitFieldInsn(GETFIELD, pojoCodecName, pfi.field.getName() + "F", "Ljava/lang/reflect/Field;");
                }
                mv.visitVarInsn(ALOAD, 2);
                mv.visitVarInsn(ALOAD, 1);
                mv.visitVarInsn(ALOAD, 0);
                String decodeName = getDecoderName((Class)pfi.field.getGenericType(), encodeType);
                if (!toInternalName(decodeName).equals(pojoCodecName)) {
                    mv.visitFieldInsn(GETFIELD, pojoCodecName, pname,
                            "L" + IFACE_NAME + ";");
                }
                visitMethod(mv, INVOKEINTERFACE, READER_NAME, "readMessage",
                        "(L" + IFACE_NAME + ";)Ljava/lang/Object;", true);
                mv.visitTypeInsn(CHECKCAST, pojo);
                visitSetValueOpcode(mv, pfi);
                if (pfi.setMethod != null
                        && !"V".equals(getDescriptor(pfi.setMethod.getGenericReturnType()))) {
                    mv.visitInsn(POP);
                }
            } else {
                if (!pfi.hasSetAccessed) {
                    mv.visitVarInsn(ALOAD, 0);
                    mv.visitFieldInsn(GETFIELD, pojoCodecName, pfi.field.getName() + "F", "Ljava/lang/reflect/Field;");
                }
                String timeUtil = toInternalName(TimeUtil.class.getName());
                mv.visitVarInsn(ALOAD, 2);
                mv.visitVarInsn(ALOAD, 1);
                visitMethod(mv, INVOKEINTERFACE, READER_NAME, readMethod.method,
                        "()" + readMethod.returnType, true);
                if ("java.util.Date".equals(pfi.field.getType().getName())) {
                    mv.visitMethodInsn(INVOKESTATIC, timeUtil, "toDate", "(J)Ljava/util/Date;", false);
                } else if ("java.util.Calendar".equals(pfi.field.getType().getName())) {
                    mv.visitMethodInsn(INVOKESTATIC, timeUtil, "toCalendar", "(J)Ljava/util/Calendar;", false);
                } else if ("java.time.LocalDateTime".equals(pfi.field.getType().getName())) {
                    mv.visitMethodInsn(INVOKESTATIC, timeUtil, "toLocalDateTime", "(J)Ljava/time/LocalDateTime;", false);
                } else {
                    visitBoxOpcode(mv, pfi);
                }
                visitSetValueOpcode(mv, pfi);
                if (pfi.setMethod != null
                        && !"V".equals(getDescriptor(pfi.setMethod.getGenericReturnType()))) {
                    mv.visitInsn(POP);
                }
            }
            mv.visitJumpInsn(GOTO, finishLabel);
        }
        if (parentMapType != null) {
            mv.visitLabel(labels[fieldCount+1]);
            String keyTypeDesc = "Ljava/lang/Object;";
            String valTypeDesc = "Ljava/lang/Object;";
            if (parentMapType instanceof ParameterizedType) {
                ParameterizedType ptype = (ParameterizedType) parentMapType;
                keyTypeDesc = getDescriptor(ptype.getActualTypeArguments()[0]);
                valTypeDesc = getDescriptor(ptype.getActualTypeArguments()[1]);
            }
            Class mapEntryCls = ProtoBufCodecRegister.INSTANCE
                    .generateMapEntryClass(parentMapType);
            String codecName = getMapCodecName(mapEntryCls);
            String mapTypeName = toInternalName(mapEntryCls.getName());

            mv.visitVarInsn(ALOAD, 1);
            mv.visitVarInsn(ALOAD, 0);
            mv.visitFieldInsn(GETFIELD, pojoCodecName, codecName,
                    "L" + IFACE_NAME + ";");
            visitMethod(mv, INVOKEINTERFACE, READER_NAME, "readMessage",
                    "(L" + IFACE_NAME + ";)Ljava/lang/Object;", true);
            mv.visitTypeInsn(CHECKCAST, mapTypeName);
            varSwitchPre++;
            mv.visitVarInsn(ASTORE, varSwitchPre);
            mv.visitVarInsn(ALOAD, 2);
            mv.visitVarInsn(ALOAD, varSwitchPre);
            mv.visitFieldInsn(GETFIELD, mapTypeName, "key", keyTypeDesc);
            mv.visitVarInsn(ALOAD, varSwitchPre);
            mv.visitFieldInsn(GETFIELD, mapTypeName, "value", valTypeDesc);
            visitMethod(mv, INVOKEVIRTUAL, pojoName, "put",
                    "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", false);
            mv.visitInsn(POP);
            mv.visitJumpInsn(GOTO, finishLabel);
        }

        mv.visitLabel(dfltLabel);
        mv.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
        mv.visitVarInsn(ALOAD, varReader);
        mv.visitVarInsn(ILOAD, varTag);
        mv.visitVarInsn(ILOAD, varTag);
        visitMethod(mv, INVOKEINTERFACE, READER_NAME, "skipField",
                "(II)Z", true);
        mv.visitInsn(POP);
        //mv.visitJumpInsn(GOTO, forLabel);
        //结束switch语句

        mv.visitLabel(finishLabel);
        mv.visitFrame(Opcodes.F_CHOP,1, null, 0, null);
        mv.visitJumpInsn(GOTO, l0);
        //mv.visitFrame(Opcodes.F_FULL, framObjs.size(), framObjs.toArray(), 0,
        //        new Object[] {});




        mv.visitLabel(l1);
        mv.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
        //为Pojo对象赋值Map，List和Array的值
        for (int i=0;i<listFields.size();i++) {
            ProtoFieldInfo pfi = listFields.get(i);
            if (!pfi.hasSetAccessed) {
                mv.visitVarInsn(ALOAD, 0);
                mv.visitFieldInsn(GETFIELD, pojoCodecName, pfi.field.getName() + "F", "Ljava/lang/reflect/Field;");
            }
            mv.visitVarInsn(ALOAD, varPojo);
            mv.visitVarInsn(ALOAD, aListTag.get(pfi.protoField.tag()));
            visitSetValueOpcode(mv, pfi);
            if (pfi.setMethod != null
                    && !"V".equals(getDescriptor(pfi.setMethod.getGenericReturnType()))) {
                mv.visitInsn(POP);
            }
        }

        for (int i=0;i<mapFields.size();i++) {
            ProtoFieldInfo pfi = mapFields.get(i);
            if (!pfi.hasSetAccessed) {
                mv.visitVarInsn(ALOAD, 0);
                mv.visitFieldInsn(GETFIELD, pojoCodecName, pfi.field.getName() + "F", "Ljava/lang/reflect/Field;");
            }
            mv.visitVarInsn(ALOAD, varPojo);
            mv.visitVarInsn(ALOAD, aMapTag.get(pfi.protoField.tag()));
            visitSetValueOpcode(mv, pfi);

        }

        for (int i=0;i<arrayFields.size();i++) {
            ProtoFieldInfo pfi = arrayFields.get(i);
            String fname = "ARR_TPL_" + i;

            mv.visitVarInsn(ALOAD, aArrayTag.get(pfi.protoField.tag()));
            visitMethod(mv, INVOKESTATIC, COLLECTION_UTIL, "isEmpty",
                    "(Ljava/util/Collection;)Z", false);
            if (!pfi.hasSetAccessed) {
                mv.visitVarInsn(ALOAD, 0);
                mv.visitFieldInsn(GETFIELD, pojoCodecName, pfi.field.getName() + "F", "Ljava/lang/reflect/Field;");
            }
            mv.visitVarInsn(ALOAD, varPojo);
            mv.visitVarInsn(ALOAD, aArrayTag.get(pfi.protoField.tag()));
            String typeDesc = getDescriptor(pfi.field.getGenericType());
            mv.visitFieldInsn(GETSTATIC, pojoCodecName, fname, typeDesc);
            visitMethod(mv, INVOKEINTERFACE, "java/util/List", "toArray",
                    "([Ljava/lang/Object;)[Ljava/lang/Object;", true);
            mv.visitTypeInsn(CHECKCAST, typeDesc);
            visitSetValueOpcode(mv, pfi);

        }

        // 为String类型增加默认值""
        System.out.println("stringFields.size=" + stringFields.size());
        for (int i=0;i<stringFields.size();i++) {
            ProtoFieldInfo pfi = stringFields.get(i);
//            mv.visitVarInsn(ALOAD, varPojo);
//            mv.visitFieldInsn(GETFIELD, "io/edap/x/protobuf/tutorial/OneString", "field1", "Ljava/lang/String;");
            String rType = "Ljava/lang/String;";
            visitGetFieldValue(mv, pfi, pojoName, pojoCodecName, varPojo, rType);
            Label l6 = new Label();
            mv.visitJumpInsn(IFNONNULL, l6);
//            mv.visitVarInsn(ALOAD, varPojo);
//            mv.visitLdcInsn("");
//            mv.visitFieldInsn(PUTFIELD, pojoCodecName, stringFields.get(i).field.getName(), "Ljava/lang/String;");
            if (!pfi.hasSetAccessed) {
                mv.visitVarInsn(ALOAD, 0);
                mv.visitFieldInsn(GETFIELD, pojoCodecName, pfi.field.getName() + "F", "Ljava/lang/reflect/Field;");
            }
            mv.visitVarInsn(ALOAD, varPojo);
            mv.visitLdcInsn("");
            visitSetValueOpcode(mv, pfi);
//            mv.visitMethodInsn(INVOKEVIRTUAL, "io/edap/x/protobuf/tutorial/OneString", "setField1", "(Ljava/lang/String;)V", false);
//            mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/reflect/Field", "set", "(Ljava/lang/Object;Ljava/lang/Object;)V", false);
            mv.visitLabel(l6);
        }

        mv.visitVarInsn(ALOAD, varPojo);
        mv.visitInsn(ARETURN);
        mv.visitMaxs(3, 9);
        mv.visitEnd();
    }

    private boolean isPrimitive(ProtoFieldInfo pfi) {
        boolean isPrimitive = false;
        java.lang.reflect.Type itemType = pfi.field.getType().getComponentType();
        if (itemType instanceof Class) {
            if (((Class)itemType).isPrimitive()) {
                isPrimitive = true;
            }
        }
        if (pfi.protoField.type() == Type.FIXED32
                || pfi.protoField.type() == Type.FIXED64
                || pfi.protoField.type() == Type.FLOAT
                || pfi.protoField.type() == Type.INT32
                || pfi.protoField.type() == Type.INT64
                || pfi.protoField.type() == Type.SINT32
                || pfi.protoField.type() == Type.SINT64
                || pfi.protoField.type() == Type.UINT32
                || pfi.protoField.type() == Type.UINT64
                || pfi.protoField.type() == Type.SFIXED32
                || pfi.protoField.type() == Type.SFIXED64) {
            isPrimitive = true;
        }
        return isPrimitive;
    }

    private List<String> methodEnums = new ArrayList<>();
    private String getConvertEnumName(Class cls) {
        int index = methodEnums.indexOf(cls.getName());
        String name = "convertEnums_";
        if (index == -1) {
            name += methodEnums.size();
        } else {
            name += index;
        }
        return name;
    }

    private List<String> methodArrayEnums = new ArrayList<>();
    private String getConvertEnumArrayName(Class cls) {
        int index = methodArrayEnums.indexOf(cls.getName());
        String name = "convertEnumsArray_";
        if (index == -1) {
            name += methodArrayEnums.size();
        } else {
            name += index;
        }
        return name;
    }

    private String visitConvertEnumArrayMethod(ProtoFieldInfo pfi) {
        MethodVisitor mv;
        String name = getConvertEnumArrayName(pfi.field.getType());

        java.lang.reflect.Type itemType = pfi.field.getType().getComponentType();
        String itemTypeName = toInternalName(((Class)itemType).getName());
        String typedesc = getDescriptor(pfi.field.getGenericType());

        mv = cw.visitMethod(ACC_PRIVATE, name, "([I)" + typedesc, null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 1);
        mv.visitInsn(ARRAYLENGTH);
        mv.visitVarInsn(ISTORE, 2);
        mv.visitVarInsn(ILOAD, 2);
        mv.visitTypeInsn(ANEWARRAY, itemTypeName);
        mv.visitVarInsn(ASTORE, 3);
        mv.visitInsn(ICONST_0);
        mv.visitVarInsn(ISTORE, 4);
        Label l0 = new Label();
        mv.visitLabel(l0);
        mv.visitFrame(Opcodes.F_APPEND,3, new Object[] {Opcodes.INTEGER, typedesc, Opcodes.INTEGER}, 0, null);
        mv.visitVarInsn(ILOAD, 4);
        mv.visitVarInsn(ILOAD, 2);
        Label l1 = new Label();
        mv.visitJumpInsn(IF_ICMPGE, l1);
        mv.visitVarInsn(ALOAD, 3);
        mv.visitVarInsn(ILOAD, 4);

        if (implInterface(pfi.field.getType(), ProtoBufEnum.class)) {
            mv.visitVarInsn(ALOAD, 1);
            mv.visitVarInsn(ILOAD, 4);
            mv.visitInsn(IALOAD);
            mv.visitMethodInsn(INVOKESTATIC, itemTypeName, "valueOf", "(I)L" + itemTypeName + ";", false);
        } else {
            mv.visitMethodInsn(INVOKESTATIC, itemTypeName, "values", "()" + typedesc, false);
            mv.visitVarInsn(ALOAD, 1);
            mv.visitVarInsn(ILOAD, 4);
            mv.visitInsn(IALOAD);
            mv.visitInsn(AALOAD);
        }

        mv.visitInsn(AASTORE);
        mv.visitIincInsn(4, 1);
        mv.visitJumpInsn(GOTO, l0);
        mv.visitLabel(l1);
        mv.visitFrame(Opcodes.F_CHOP,1, null, 0, null);
        mv.visitVarInsn(ALOAD, 3);
        mv.visitInsn(ARETURN);
        mv.visitMaxs(5, 5);
        mv.visitEnd();

        methodArrayEnums.add(pfi.field.getType().getName());
        return name;
    }

    private String visitConvertEnumMethod(ProtoFieldInfo pfi) {
        MethodVisitor mv;
        String name = getConvertEnumName(pfi.field.getType());
        ParameterizedType ptype = (ParameterizedType)pfi.field.getGenericType();
        java.lang.reflect.Type itemType = ptype.getActualTypeArguments()[0];
        String ename = toInternalName(((Class)itemType).getName());
        mv = cw.visitMethod(ACC_PRIVATE, getConvertEnumName(pfi.field.getType()),
                "(Ljava/util/List;)Ljava/util/List;", "(Ljava/util/List<Ljava/lang/Integer;>;)" +
                        "Ljava/util/List<L" + ename + ";>;", null);
        mv.visitCode();
        mv.visitTypeInsn(NEW, "java/util/ArrayList");
        mv.visitInsn(DUP);
        mv.visitMethodInsn(INVOKESPECIAL, "java/util/ArrayList", "<init>", "()V", false);
        mv.visitVarInsn(ASTORE, 2);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitMethodInsn(INVOKEINTERFACE, "java/util/List", "iterator", "()Ljava/util/Iterator;", true);
        mv.visitVarInsn(ASTORE, 3);
        Label l0 = new Label();
        mv.visitLabel(l0);
        mv.visitFrame(Opcodes.F_APPEND,2, new Object[] {"java/util/List", "java/util/Iterator"}, 0, null);
        mv.visitVarInsn(ALOAD, 3);
        mv.visitMethodInsn(INVOKEINTERFACE, "java/util/Iterator", "hasNext", "()Z", true);
        Label l1 = new Label();
        mv.visitJumpInsn(IFEQ, l1);
        mv.visitVarInsn(ALOAD, 3);
        mv.visitMethodInsn(INVOKEINTERFACE, "java/util/Iterator", "next", "()Ljava/lang/Object;", true);
        mv.visitTypeInsn(CHECKCAST, "java/lang/Integer");
        mv.visitVarInsn(ASTORE, 4);
        mv.visitVarInsn(ALOAD, 2);
        if (implInterface(pfi.field.getType(), ProtoBufEnum.class)) {
            mv.visitVarInsn(ALOAD, 4);
            mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Integer", "intValue", "()I", false);
            mv.visitMethodInsn(INVOKESTATIC, ename, "valueOf", "(I)L" + ename + ";", false);
        } else {
            mv.visitMethodInsn(INVOKESTATIC, ename, "values", "()[L" + ename + ";", false);
            mv.visitVarInsn(ALOAD, 4);
            mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Integer", "intValue", "()I", false);
            mv.visitInsn(AALOAD);
        }
        mv.visitMethodInsn(INVOKEINTERFACE, "java/util/List", "add", "(Ljava/lang/Object;)Z", true);
        mv.visitInsn(POP);
        mv.visitJumpInsn(GOTO, l0);
        mv.visitLabel(l1);
        mv.visitFrame(Opcodes.F_CHOP,1, null, 0, null);
        mv.visitVarInsn(ALOAD, 2);
        mv.visitInsn(ARETURN);
        mv.visitMaxs(3, 5);
        mv.visitEnd();

        methodEnums.add(pfi.field.getType().getName());
        return name;
    }

    /**
     * 添加基础数据类型如int,long,double等数据的装箱的opcode
     * @param mv
     * @param pfi
     */
    private void visitBoxOpcode(MethodVisitor mv, ProtoFieldInfo pfi) {
        visitBoxOpcode(mv, pfi, null);
    }

    /**
     * 添加基础数据类型如int,long,double等数据的装箱的opcode
     * @param mv
     * @param pfi
     */
    private void visitBoxOpcode(MethodVisitor mv, ProtoFieldInfo pfi,
                                java.lang.reflect.Type itemType) {
        String name = pfi.field.getType().getName();
        if (itemType != null) {
            name = itemType.getTypeName();
        }
        switch (name) {
            case "java.lang.Integer":
                visitMethod(mv, INVOKESTATIC, "java/lang/Integer", "valueOf",
                        "(I)Ljava/lang/Integer;", false);
                break;
            case "java.lang.Long":
                visitMethod(mv, INVOKESTATIC, "java/lang/Long", "valueOf",
                        "(J)Ljava/lang/Long;", false);
                break;
            case "java.lang.Boolean":
                visitMethod(mv, INVOKESTATIC, "java/lang/Boolean", "valueOf",
                        "(Z)Ljava/lang/Boolean;", false);
                break;
            case "java.lang.Float":
                visitMethod(mv, INVOKESTATIC, "java/lang/Float", "valueOf",
                        "(F)Ljava/lang/Float;", false);
                break;
            case "java.lang.Double":
                visitMethod(mv, INVOKESTATIC, "java/lang/Double", "valueOf",
                        "(D)Ljava/lang/Double;", false);
                break;
            case "java.lang.Short":
                visitMethod(mv, INVOKESTATIC, "java/lang/Double", "valueOf",
                        "(S)Ljava/lang/Short;", false);
                break;
        }
    }

    private void visitSetValueOpcode(MethodVisitor mv, ProtoFieldInfo pfi) {
        String valType = getDescriptor(pfi.field.getType());
        if (pfi.hasSetAccessed) {
            if (pfi.setMethod != null) {
                String rtnDesc = getDescriptor(pfi.setMethod.getGenericReturnType());
                visitMethod(mv, INVOKEVIRTUAL, pojoName, pfi.setMethod.getName(),
                        "(" + valType + ")" + rtnDesc, false);
            } else {
                mv.visitFieldInsn(PUTFIELD, pojoName, pfi.field.getName(),
                        valType);
            }
        } else {
            System.out.println(pfi.field.getType().getName());
            switch (pfi.field.getType().getName()) {
                case "boolean":
                    visitMethod(mv, INVOKESTATIC, "java/lang/Boolean",
                            "valueOf", "(Z)Ljava/lang/Boolean;", false);
                    break;
                case "double":
                    visitMethod(mv, INVOKESTATIC, "java/lang/Double",
                            "valueOf", "(D)Ljava/lang/Double;", false);
                    break;
                case "int":
                    visitMethod(mv, INVOKESTATIC, "java/lang/Integer",
                            "valueOf", "(I)Ljava/lang/Integer;", false);
                    break;
                case "long":
                    visitMethod(mv, INVOKESTATIC, "java/lang/Long",
                            "valueOf", "(J)Ljava/lang/Long;", false);
                    break;
                case "float":
                    visitMethod(mv, INVOKESTATIC, "java/lang/Float",
                            "valueOf", "(F)Ljava/lang/Float;", false);
                    break;
            }
            mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/reflect/Field", "set",
                    "(Ljava/lang/Object;Ljava/lang/Object;)V", false);
        }
    }

    class ReadMethodInfo {
        private String method;
        private String paramType;
        private String returnType;
    }

    private ReadMethodInfo getProtoReadMethod(ProtoFieldInfo pfi) {
        boolean isArray = false;
        boolean isList = false;
        if (pfi.field.getType().isArray()
                && !"[B".equals(pfi.field.getType().getName())) {
            isArray = true;
        } else if (AsmUtil.isList(pfi.field.getGenericType())) {
            isList = true;
        }
        ReadMethodInfo rmi = new ReadMethodInfo();
        rmi.paramType = "";
        switch (pfi.protoField.type()) {
            case FLOAT:
                if (isArray) {
                    if ("[Ljava/lang/Float;".equals(getDescriptor(pfi.field.getGenericType()))) {
                        rmi.method = "readPackedFloatArray";
                        rmi.returnType = "[Ljava/lang/Float;";
                    } else {
                        rmi.method = "readPackedFloatArrayValue";
                        rmi.returnType = "[F";
                    }
                } else if (isList) {
                    rmi.method = "readPackedFloat";
                    rmi.returnType = "Ljava/util/List;";
                } else {
                    rmi.method = "readFloat";
                    rmi.returnType = "F";
                }
                break;
            case DOUBLE:
                if (isList) {
                    rmi.method = "readPackedDouble";
                    rmi.returnType = "Ljava/util/List;";
                } else if (isArray) {
                    if ("[Ljava/lang/Double;".equals(getDescriptor(pfi.field.getGenericType()))) {
                        rmi.method = "readPackedDoubleArray";
                        rmi.returnType = "[Ljava/lang/Double;";
                    } else {
                        rmi.method = "readPackedDoubleArrayValue";
                        rmi.returnType = "[D";
                    }
                } else {
                    rmi.method = "readDouble";
                    rmi.returnType = "D";
                }
                break;
            case INT32:
                if (isArray) {
                    if ("[Ljava/lang/Integer;".equals(getDescriptor(pfi.field.getGenericType()))) {
                        rmi.method = "readPackedInt32Array";
                        rmi.returnType = "[Ljava/lang/Integer;";
                        rmi.paramType = "L" + FIELD_TYPE_NAME + ";";
                    } else {
                        rmi.method = "readPackedInt32ArrayValue";
                        rmi.returnType = "[I";
                        rmi.paramType = "L" + FIELD_TYPE_NAME + ";";
                    }
                } else if (isList) {
                    rmi.method = "readPackedInt32";
                    rmi.returnType = "Ljava/util/List;";
                    rmi.paramType = "L" + FIELD_TYPE_NAME + ";";
                } else {
                    rmi.method = "readInt32";
                    rmi.returnType = "I";
                }
                break;
            case INT64:
                if (isArray) {
                    if ("[Ljava/lang/Long;".equals(getDescriptor(pfi.field.getGenericType()))) {
                        rmi.method = "readPackedInt64Array";
                        rmi.returnType = "[Ljava/lang/Long;";
                        rmi.paramType = "L" + FIELD_TYPE_NAME + ";";
                    } else {
                        rmi.method = "readPackedInt64ArrayValue";
                        rmi.returnType = "[J";
                        rmi.paramType = "L" + FIELD_TYPE_NAME + ";";
                    }
                } else if (isList) {
                    rmi.method = "readPackedInt64";
                    rmi.returnType = "Ljava/util/List;";
                    rmi.paramType = "L" + FIELD_TYPE_NAME + ";";
                } else {
                    rmi.method = "readInt64";
                    rmi.returnType = "J";
                }
                break;
            case UINT32:
                if (isArray) {
                    if ("[Ljava/lang/Integer;".equals(getDescriptor(pfi.field.getGenericType()))) {
                        rmi.method = "readPackedInt32Array";
                        rmi.returnType = "[Ljava/lang/Integer;";
                        rmi.paramType = "L" + FIELD_TYPE_NAME + ";";
                    } else {
                        rmi.method = "readPackedInt32ArrayValue";
                        rmi.returnType = "[I";
                        rmi.paramType = "L" + FIELD_TYPE_NAME + ";";
                    }
                } else if (isList) {
                    rmi.method = "readPackedInt32";
                    rmi.returnType = "Ljava/util/List;";
                    rmi.paramType = "L" + FIELD_TYPE_NAME + ";";
                } else {
                    rmi.method = "readUInt32";
                    rmi.returnType = "I";
                }
                break;
            case UINT64:
                if (isArray) {
                    if ("[Ljava/lang/Long;".equals(getDescriptor(pfi.field.getGenericType()))) {
                        rmi.method = "readPackedInt64Array";
                        rmi.returnType = "[Ljava/lang/Long;";
                        rmi.paramType = "L" + FIELD_TYPE_NAME + ";";
                    } else {
                        rmi.method = "readPackedInt64ArrayValue";
                        rmi.returnType = "[J";
                        rmi.paramType = "L" + FIELD_TYPE_NAME + ";";
                    }
                } else if (isList) {
                    rmi.method = "readPackedInt64";
                    rmi.returnType = "Ljava/util/List;";
                    rmi.paramType = "L" + FIELD_TYPE_NAME + ";";
                } else {
                    rmi.method = "readUInt64";
                    rmi.returnType = "J";
                }
                break;
            case SINT32:
                if (isArray) {
                    if ("[Ljava/lang/Integer;".equals(getDescriptor(pfi.field.getGenericType()))) {
                        rmi.method = "readPackedInt32Array";
                        rmi.returnType = "[Ljava/lang/Integer;";
                        rmi.paramType = "L" + FIELD_TYPE_NAME + ";";
                    } else {
                        rmi.method = "readPackedInt32ArrayValue";
                        rmi.returnType = "[I";
                        rmi.paramType = "L" + FIELD_TYPE_NAME + ";";
                    }
                } else if (isList) {
                    rmi.method = "readPackedInt32";
                    rmi.returnType = "Ljava/util/List;";
                    rmi.paramType = "L" + FIELD_TYPE_NAME + ";";
                } else {
                    rmi.method = "readSInt32";
                    rmi.returnType = "I";
                }
                break;
            case SINT64:
                if (isArray) {
                    if ("[Ljava/lang/Long;".equals(getDescriptor(pfi.field.getGenericType()))) {
                        rmi.method = "readPackedInt64Array";
                        rmi.returnType = "[Ljava/lang/Long;";
                        rmi.paramType = "L" + FIELD_TYPE_NAME + ";";
                    } else {
                        rmi.method = "readPackedInt64ArrayValue";
                        rmi.returnType = "[J";
                        rmi.paramType = "L" + FIELD_TYPE_NAME + ";";
                    }
                } else if (isList) {
                    rmi.method = "readPackedInt64";
                    rmi.returnType = "Ljava/util/List;";
                    rmi.paramType = "L" + FIELD_TYPE_NAME + ";";
                } else {
                    rmi.method = "readSInt64";
                    rmi.returnType = "J";
                }
                break;
            case FIXED32:
                if (isList) {
                    rmi.method = "readPackedInt32";
                    rmi.returnType = "Ljava/util/List;";
                    rmi.paramType = "L" + FIELD_TYPE_NAME + ";";
                } else if (isArray) {
                    if ("[Ljava/lang/Integer;".equals(getDescriptor(pfi.field.getGenericType()))) {
                        rmi.method = "readPackedInt32Array";
                        rmi.returnType = "[Ljava/lang/Integer;";
                        rmi.paramType = "L" + FIELD_TYPE_NAME + ";";
                    } else {
                        rmi.method = "readPackedInt32ArrayValue";
                        rmi.returnType = "[I";
                        rmi.paramType = "L" + FIELD_TYPE_NAME + ";";
                    }
                } else {
                    rmi.method = "readFixed32";
                    rmi.returnType = "I";
                }
                break;
            case FIXED64:
                if (isList) {
                    rmi.method = "readPackedInt64";
                    rmi.returnType = "Ljava/util/List;";
                    rmi.paramType = "L" + FIELD_TYPE_NAME + ";";
                } else if (isArray) {
                    if ("[Ljava/lang/Long;".equals(getDescriptor(pfi.field.getGenericType()))) {
                        rmi.method = "readPackedInt64Array";
                        rmi.returnType = "[Ljava/lang/Long;";
                        rmi.paramType = "L" + FIELD_TYPE_NAME + ";";
                    } else {
                        rmi.method = "readPackedInt64ArrayValue";
                        rmi.returnType = "[J";
                        rmi.paramType = "L" + FIELD_TYPE_NAME + ";";
                    }
                } else {
                    rmi.method = "readFixed64";
                    rmi.returnType = "J";
                }
                break;
            case SFIXED32:
                if (isList) {
                    rmi.method = "readPackedInt32";
                    rmi.returnType = "Ljava/util/List;";
                    rmi.paramType = "L" + FIELD_TYPE_NAME + ";";
                } else if (isArray) {
                    if ("[Ljava/lang/Integer;".equals(getDescriptor(pfi.field.getGenericType()))) {
                        rmi.method = "readPackedInt32Array";
                        rmi.returnType = "[Ljava/lang/Integer;";
                        rmi.paramType = "L" + FIELD_TYPE_NAME + ";";
                    } else {
                        rmi.method = "readPackedInt32ArrayValue";
                        rmi.returnType = "[I";
                        rmi.paramType = "L" + FIELD_TYPE_NAME + ";";
                    }
                } else {
                    rmi.method = "readSFixed32";
                    rmi.returnType = "I";
                }
                break;
            case SFIXED64:
                if (isList) {
                    rmi.method = "readPackedInt64";
                    rmi.returnType = "Ljava/util/List;";
                    rmi.paramType = "L" + FIELD_TYPE_NAME + ";";
                } else if (isArray) {
                    if ("[Ljava/lang/Long;".equals(getDescriptor(pfi.field.getGenericType()))) {
                        rmi.method = "readPackedInt64Array";
                        rmi.returnType = "[Ljava/lang/Long;";
                        rmi.paramType = "L" + FIELD_TYPE_NAME + ";";
                    } else {
                        rmi.method = "readPackedInt64ArrayValue";
                        rmi.returnType = "[J";
                        rmi.paramType = "L" + FIELD_TYPE_NAME + ";";
                    }
                } else {
                    rmi.method = "readSFixed64";
                    rmi.returnType = "J";
                }
                break;
            case BOOL:
                if (isArray) {
                } else if (isList) {
                    rmi.method = "readPackedBool";
                    rmi.returnType = "Ljava/util/List;";
                } else {
                    rmi.method = "readBool";
                    rmi.returnType = "Z";
                }
                break;
            case ENUM:
                if (isList) {
                    rmi.method = "readEnums";
                    rmi.returnType = "Ljava/util/List;";
                } else if (isArray) {

                } else {
                    rmi.method = "readInt32";
                    rmi.returnType = "I";
                }
                break;
            case STRING:
                rmi.method = "readString";
                rmi.returnType = "Ljava/lang/String;";
                break;
            case BYTES:
                rmi.method = "readBytes";
                rmi.returnType = "[B";
                break;
            case MESSAGE:
                rmi.method = "readMessage";
                rmi.returnType = getDescriptor(pfi.field.getGenericType());
                break;
            case OBJECT:
                if (isList) {
                    rmi.method = "readObject";
                    rmi.returnType = getDescriptor(Object.class);
                } else {
                    rmi.method = "readObject";
                    rmi.returnType = getDescriptor(pfi.field.getGenericType());
                }
                break;
            case GROUP:

                break;
            case MAP:
                java.lang.reflect.Type mapType = pfi.field.getGenericType();

                rmi.method = "readMap";
                rmi.returnType = "";
                break;
            default:
                break;
        }
        return rmi;
    }

    private void visitDecodeBridgeMethod() {
        MethodVisitor mv;
        mv = cw.visitMethod(ACC_PUBLIC + ACC_BRIDGE + ACC_SYNTHETIC, "decode",
                "(L" + READER_NAME + ";)Ljava/lang/Object;", null, null);

        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitVarInsn(ALOAD, 1);
        visitMethod(mv, INVOKEVIRTUAL, pojoCodecName,
                "decode", "(L" + READER_NAME + ";)L"
                        + toInternalName(pojoCls.getName())
                        + ";", false);
        mv.visitInsn(ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    private void visitInitMethod(List<java.lang.reflect.Type> pojoTypes, List<ProtoFieldInfo> fields) {

        for (java.lang.reflect.Type type : pojoTypes) {
            String itemType = toInternalName(((Class)type).getName());
            String codecName = getPojoDecoderName(type);
            String encoderName = toInternalName(getDecoderName((Class)type, encodeType));
            if (!encoderName.equals(pojoCodecName)) {
                cw.visitField(ACC_PRIVATE, codecName, "L" + IFACE_NAME + ";",
                        "L" + IFACE_NAME + "<L" + itemType + ";>;", null);
            }
        }
        Map<String, String> mapNames = new HashMap<>();
        for (ProtoFieldInfo pfi : mapFields) {
            Class mapCls = ProtoBufCodecRegister.INSTANCE
                    .generateMapEntryClass(pfi.field.getGenericType());
            String codecName = getMapCodecName(mapCls);
            if (!mapNames.containsKey(codecName)) {
                String itemType = toInternalName(mapCls.getName());
                cw.visitField(ACC_PRIVATE, codecName, "L" + IFACE_NAME + ";",
                        "L" + IFACE_NAME + "<L" + itemType + ";>;", null);
                String typeName = getDescriptor(mapCls);
                mapNames.put(codecName, typeName);
            }
        }
        if (parentMapType != null) {
            Class mapCls = ProtoBufCodecRegister.INSTANCE
                    .generateMapEntryClass(parentMapType);
            String codecName = getMapCodecName(mapCls);
            if (!mapNames.containsKey(codecName)) {
                String itemType = toInternalName(mapCls.getName());
                cw.visitField(ACC_PRIVATE, codecName, "L" + IFACE_NAME + ";",
                        "L" + IFACE_NAME + "<L" + itemType + ";>;", null);
                String typeName = getDescriptor(mapCls);
                mapNames.put(codecName, typeName);
            }
        }

        MethodVisitor mv;
        mv = cw.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        visitMethod(mv, INVOKESPECIAL, parentName, "<init>", "()V", false);

        String getCodecName;
        if (encodeType == ProtoBuf.EncodeType.FAST) {
            getCodecName = "getFastDecoder";
        } else {
            getCodecName = "getDecoder";
        }
        for (java.lang.reflect.Type type : pojoTypes) {
            String codecName = getPojoDecoderName(type);
            String encoderName = toInternalName(getDecoderName((Class)type, encodeType));
            if (!encoderName.equals(pojoCodecName)) {
                mv.visitVarInsn(ALOAD, 0);
                mv.visitFieldInsn(GETSTATIC, REGISTER_NAME,
                        "INSTANCE", "L" + REGISTER_NAME + ";");
                String tdescriptor = getDescriptor(type);
                mv.visitLdcInsn(org.objectweb.asm.Type.getType(tdescriptor));
                visitMethod(mv, INVOKEVIRTUAL, REGISTER_NAME, getCodecName,
                        "(Ljava/lang/Class;)L" + IFACE_NAME + ";", false);
                mv.visitFieldInsn(PUTFIELD, pojoCodecName,
                        codecName, "L" + IFACE_NAME + ";");
            }
        }
        for (Map.Entry<String, String> mapFields : mapNames.entrySet()) {
            mv.visitVarInsn(ALOAD, 0);
            mv.visitFieldInsn(GETSTATIC, REGISTER_NAME,
                    "INSTANCE", "L" + REGISTER_NAME + ";");
            mv.visitLdcInsn(org.objectweb.asm.Type.getType(mapFields.getValue()));
            visitMethod(mv, INVOKEVIRTUAL, REGISTER_NAME, getCodecName,
                    "(Ljava/lang/Class;)L" + IFACE_NAME + ";", false);
            mv.visitFieldInsn(PUTFIELD, pojoCodecName,
                    mapFields.getKey(), "L" + IFACE_NAME + ";");
        }
        if (parentMapType != null) {
            Class mapCls = ProtoBufCodecRegister.INSTANCE
                    .generateMapEntryClass(parentMapType);
            String codecName = getMapCodecName(mapCls);
            String itemType = toInternalName(mapCls.getName());
            if (!mapNames.containsKey(codecName)) {
                cw.visitField(ACC_PRIVATE, codecName, "L" + IFACE_NAME + ";",
                        "L" + IFACE_NAME + "<L" + itemType + ";>;", null);
                String typeName = getDescriptor(mapCls);
                mapNames.put(codecName, typeName);
            }
            mv.visitVarInsn(ALOAD, 0);
            mv.visitFieldInsn(GETSTATIC, REGISTER_NAME, "INSTANCE", "L" + REGISTER_NAME + ";");
            mv.visitLdcInsn(org.objectweb.asm.Type.getType("L" + itemType + ";"));
            mv.visitMethodInsn(INVOKEVIRTUAL, REGISTER_NAME, "getDecoder", "(Ljava/lang/Class;)L" + IFACE_NAME + ";", false);
            mv.visitFieldInsn(PUTFIELD, pojoCodecName, codecName, "L" + IFACE_NAME + ";");
        }

        visitReflectField(mv, fields);

        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    private void visitReflectField(MethodVisitor mv, List<ProtoFieldInfo> fields) {
        List<ProtoFieldInfo> needReflectFields = new ArrayList<>();
        for (ProtoFieldInfo pfi : fields) {
            if (!pfi.hasGetAccessed) {
                needReflectFields.add(pfi);
            }
        }
        if (CollectionUtils.isEmpty(needReflectFields)) {
            return;
        }
        FieldVisitor fv;


        Label l0 = new Label();
        Label l1 = new Label();
        Label l2 = new Label();
        mv.visitTryCatchBlock(l0, l1, l2, "java/lang/NoSuchFieldException");
        mv.visitLabel(l0);
        for (ProtoFieldInfo pfi : needReflectFields) {
            fv = cw.visitField(0, pfi.field.getName() + "F",
                    "Ljava/lang/reflect/Field;", null, null);
            fv.visitEnd();

            mv.visitVarInsn(ALOAD, 0);
            mv.visitLdcInsn(org.objectweb.asm.Type.getType("L" + pojoName + ";"));
            mv.visitLdcInsn(pfi.field.getName());
            visitMethod(mv, INVOKESTATIC, CLAZZ_UTIL_NAME, "getDeclaredField",
                    "(Ljava/lang/Class;Ljava/lang/String;)Ljava/lang/reflect/Field;", false);
            mv.visitFieldInsn(PUTFIELD, pojoCodecName, pfi.field.getName() + "F", "Ljava/lang/reflect/Field;");
            mv.visitVarInsn(ALOAD, 0);
            mv.visitFieldInsn(GETFIELD, pojoCodecName, pfi.field.getName() + "F", "Ljava/lang/reflect/Field;");
            mv.visitInsn(ICONST_1);
            mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/reflect/Field", "setAccessible", "(Z)V", false);
        }
        mv.visitLabel(l1);
        Label l3 = new Label();
        mv.visitJumpInsn(GOTO, l3);
        mv.visitLabel(l2);
        mv.visitFrame(Opcodes.F_FULL, 1, new Object[] {"io/edap/x/protobuf/NoGetMethodEncoder"}, 1, new Object[] {"java/lang/NoSuchFieldException"});
        mv.visitVarInsn(ASTORE, 1);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/NoSuchFieldException", "printStackTrace", "()V", false);
        mv.visitLabel(l3);
        mv.visitFrame(Opcodes.F_SAME, 0, null, 0, null);


    }

    private String getMapCodecName(Class mapCls) {
        return "DecoderMap" + mapCls.getSimpleName();
    }

    private String getPojoDecoderName(java.lang.reflect.Type type) {
        return "Decoder" + ((Class)type).getSimpleName();
    }

    private void visitClinitMethod() {
        FieldVisitor fv;
        MethodVisitor mv;
        mv = cw.visitMethod(ACC_STATIC, "<clinit>", "()V", null, null);
        mv.visitCode();
        int innerClsCount = 0;
        for (int i=0;i<arrayFields.size();i++) {
            ProtoFieldInfo pfi = arrayFields.get(i);
            String fname = "ARR_LIST_" + i;
            Class itemCls = (Class)pfi.field.getType().getComponentType();
            String itemName = "";
            if (itemCls.isPrimitive()) {
                itemName = getDescriptor(itemCls);
            } else {
                itemName = itemCls.getName();
            }

            String itemType = toInternalName(itemName);
            String itemDesc = getDescriptor(pfi.field.getType().getComponentType());

            String tplName = "ARR_TPL_" + i;
            String tplDesc = getDescriptor(pfi.field.getGenericType());
            fv = cw.visitField(ACC_PRIVATE + ACC_FINAL + ACC_STATIC, tplName,
                    tplDesc, null, null);
            fv.visitEnd();

            fv = cw.visitField(ACC_FINAL + ACC_STATIC, fname,
                    "Ljava/lang/ThreadLocal;",
                    "Ljava/lang/ThreadLocal<Ljava/util/List<" + itemDesc
                            + ">;>;", null);
            fv.visitEnd();

            String innerClsName = pojoCodecName + "$" + (i+1);
            java.lang.reflect.Type[] actualTypes = new java.lang.reflect.Type[1];
            actualTypes[0] = (Class)pfi.field.getType().getComponentType();
            java.lang.reflect.Type localType = buildType(
                    List.class, actualTypes);
            CodecThreadLocalGernerator ctlg = new CodecThreadLocalGernerator(
                    localType, innerClsName);
            inners.add(ctlg.getClassInfo());
            cw.visitInnerClass(innerClsName, null, null, 0);

            mv.visitInsn(ICONST_0);
            mv.visitTypeInsn(ANEWARRAY, itemType);
            mv.visitFieldInsn(PUTSTATIC, pojoCodecName, tplName, tplDesc);


            mv.visitTypeInsn(NEW, innerClsName);
            mv.visitInsn(DUP);
            visitMethod(mv, INVOKESPECIAL, innerClsName, "<init>",
                    "()V", false);
            mv.visitFieldInsn(PUTSTATIC, pojoCodecName, fname,
                    "Ljava/lang/ThreadLocal;");
            innerClsCount++;
        }

        mv.visitInsn(RETURN);
        mv.visitMaxs(2, 0);
        mv.visitEnd();
    }

    private static String getDecoderDescriptor(Class msgCls) {
        StringBuilder sb = new StringBuilder();
        sb.append(getDescriptor(AbstractDecoder.class));
        sb.append("L").append(IFACE_NAME).append("<");
        sb.append(getDescriptor(msgCls)).append(">;");
        return sb.toString();
    }

    static String getDecoderName(Class pojoCls, ProtoBuf.EncodeType encodeType) {
        if (pojoCls == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder("pbd.");
        if (pojoCls.getPackage() != null) {
            sb.append(pojoCls.getPackage().getName()).append(".");
        }
        sb.append(pojoCls.getSimpleName()).append("Decoder");
        return sb.toString();
    }
}
