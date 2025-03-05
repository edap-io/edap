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

import io.edap.protobuf.model.ProtoBufOption;
import io.edap.protobuf.util.ProtoTagComparator;
import io.edap.protobuf.util.ProtoUtil;
import io.edap.protobuf.wire.Field;
import io.edap.protobuf.wire.Field.Type;
import io.edap.protobuf.wire.WireFormat;
import io.edap.util.AsmUtil;
import io.edap.util.ClazzUtil;
import io.edap.util.CollectionUtils;
import io.edap.util.internal.GeneratorClassInfo;
import org.objectweb.asm.*;

import java.io.IOException;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.ParameterizedType;
import java.util.*;

import static io.edap.protobuf.util.ProtoUtil.*;
import static io.edap.protobuf.util.ProtoUtil.buildMapEntryEncoderName;
import static io.edap.util.AsmUtil.*;
import static io.edap.util.ClazzUtil.getDescriptor;
import static io.edap.util.ClazzUtil.getTypeName;
import static org.objectweb.asm.Opcodes.*;

public class ProtoBufEncoderGenerator {

    static final String IFACE_NAME             = toInternalName(ProtoBufEncoder.class.getName());
    static final String WRITER_NAME            = toInternalName(ProtoBufWriter.class.getName());
    static final String REGISTER_NAME          = toInternalName(ProtoBufCodecRegister.class.getName());
    static final String COLLECTION_UTIL        = toInternalName(CollectionUtils.class.getName());
    static final String FIELD_TYPE_NAME        = toInternalName(Type.class.getName());
    static final String PROTOUTIL_NAME         = toInternalName(ProtoUtil.class.getName());
    static final String CARDINALITY_NAME       = toInternalName(Field.Cardinality.class.getName());
    static final String CLAZZ_UTIL_NAME        = toInternalName(ClazzUtil.class.getName());
    static final String ENCODE_EX_NAME         = toInternalName(EncodeException.class.getName());
    static final String PROTOBUF_OPTIION_NAME  = toInternalName(ProtoBufOption.class.getName());
    static final String MAP_ENTRY_ENCODER_NAME = toInternalName(MapEntryEncoder.class.getName());

    private static final String WRITE_MAP_PREFIX      = "writeMap_";
    private static final String WRITE_LIST_PREFIX     = "writeList_";
    private static final String WRITE_ARRAY_PREFIX    = "writeArray_";
    private static final String WRITE_ITERATOR_PREFIX = "writeIterator_";
    private final HashSet codecNames   = new HashSet();
    private final HashSet codecMethods = new HashSet();

    private ClassWriter cw;
    private final Class pojoCls;
    private final ProtoBufOption option;

    private List<GeneratorClassInfo> inners;
    private String parentName;
    private String pojoName;
    private String pojoCodecName;

    private final List<ProtoFieldInfo> arrayFields    = new ArrayList<>();
    private final List<ProtoFieldInfo> listFields     = new ArrayList<>();
    private final List<ProtoFieldInfo> mapFields      = new ArrayList<>();
    private final List<ProtoFieldInfo> iterableFields = new ArrayList<>();

    private final List<String> listMethods = new ArrayList<>();
    private final List<String> arrayMethods = new ArrayList<>();
    private final List<String> mapMethods = new ArrayList<>();
    private final List<String> iteratorMethods = new ArrayList<>();
    private final Set<String>  mapEntryEncoders = new HashSet<>();
    private final List<ProtoFieldInfo> mapTypes = new ArrayList<>();

    private final java.lang.reflect.Type parentMapType;

    public ProtoBufEncoderGenerator(Class pojoCls, ProtoBufOption option) {
        this.pojoCls = pojoCls;
        this.option = option;
        this.parentMapType = ProtoUtil.parentMapType(pojoCls);
    }

    public GeneratorClassInfo getClassInfo() throws IOException {
        GeneratorClassInfo gci = new GeneratorClassInfo();
        inners = new ArrayList<>();
        pojoName = toInternalName(pojoCls.getName());
        parentName = toInternalName(AbstractEncoder.class.getName());
        pojoCodecName = toInternalName(getEncoderName(pojoCls, option));
        gci.clazzName = pojoCodecName;
        String pojoCodecDescriptor = getEncoderDescriptor(pojoCls);
        String[] ifaceName = new String[]{IFACE_NAME};

        //定义编码器名称，继承的虚拟编码器以及实现的接口
        cw = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        cw.visit(V1_8, ACC_PUBLIC + ACC_SUPER, pojoCodecName,
                pojoCodecDescriptor, parentName, ifaceName);


        List<ProtoFieldInfo> fields = ProtoUtil.getProtoFields(pojoCls);
        ProtoTagComparator ptc = new ProtoTagComparator();
        List<java.lang.reflect.Type> pojoTypes = new ArrayList<>();

        for (ProtoFieldInfo pfi : fields) {
            if (isPojo(pfi.field.getGenericType())
                    && !pojoTypes.contains(pfi.field.getGenericType())) {
                pojoTypes.add(pfi.field.getGenericType());
            } else if (isArray(pfi.field.getGenericType())) {

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
                if (isList(pfi.field.getGenericType())) {
                    java.lang.reflect.Type mapType = getMapType(pfi.field.getGenericType());
                    if (isMap(mapType) && !mapTypes.contains(mapType)) {
                        mapTypes.add(pfi);
                    }
                    listFields.add(pfi);
                } else if (isMap(pfi.field.getGenericType())) {
                    mapFields.add(pfi);
                } else if (isIterable(pfi.field.getGenericType())) {
                    iterableFields.add(pfi);
                }
            } else if (pfi.field.getGenericType() instanceof Class) {
                if (isList(pfi.field.getGenericType())) {
                    listFields.add(pfi);
                } else if (isMap(pfi.field.getGenericType())) {
                    mapFields.add(pfi);
                } else if (isIterable(pfi.field.getGenericType())) {
                    iterableFields.add(pfi);
                }
            }
        }
        visitClinitMethod(fields);
        visitInitMethod(pojoTypes, fields);
        visitGetEncoderMethods(pojoTypes);

        visitEncodeMethod(fields);

        visitEncodeBridgeMethod();

        cw.visitEnd();
        gci.inners = inners;
        gci.clazzBytes = cw.toByteArray();

        return gci;
    }

    private void visitEncodeMethod(List<ProtoFieldInfo> fields) {
        MethodVisitor mv;

        mv = cw.visitMethod(ACC_PUBLIC, "encode",
                "(L" + WRITER_NAME + ";L" + pojoName + ";)V", null, new String[] { ENCODE_EX_NAME });
        mv.visitCode();

        if (!CollectionUtils.isEmpty(fields) || parentMapType != null) {
            Label l000 = new Label();
            Label l100 = new Label();
            Label l200 = new Label();
            mv.visitTryCatchBlock(l000, l100, l200, "java/lang/Exception");
            mv.visitLabel(l000);

            for (ProtoFieldInfo pfi : fields) {
                String rType = getDescriptor(pfi.field.getType());

                if (isPojo(pfi.field.getGenericType())) {
                    mv.visitVarInsn(ALOAD, 1);
                    mv.visitFieldInsn(GETSTATIC, pojoCodecName, "tag" + pfi.protoField.tag(), "[B");
                    visitTagOpcode(mv, pfi.protoField.tag());
                    visitGetFieldValue(mv, pfi, pojoName, pojoCodecName, 2, rType);
                    String itemCodec = getPojoEncoderName(pfi.field.getGenericType());
                    mv.visitVarInsn(ALOAD, 0);

                    if (!pfi.field.getType().getName().equals(pojoCls.getName())) {
                        String codecName = getPojoEncoderName(pfi.field.getGenericType());
                        String getEncoderName = "get" + codecName.substring(0, 1).toUpperCase(Locale.ENGLISH) + codecName.substring(1);
                        mv.visitMethodInsn(INVOKESPECIAL, pojoCodecName, getEncoderName, "()L" + IFACE_NAME + ";", false);
                    }
                    visitMethod(mv, INVOKEINTERFACE, WRITER_NAME, "writeMessage",
                            "([BILjava/lang/Object;L" + IFACE_NAME + ";)V", true);
                } else {
                    String writeMethod = getWriteMethod(pfi.protoField.type());
                    if ("writeEnum".equals(writeMethod) && !isList(pfi.field.getGenericType()) &&
                            !isArray(pfi.field.getType())) {
                        mv.visitVarInsn(ALOAD, 1);
                        mv.visitFieldInsn(GETSTATIC, pojoCodecName, "tag" + pfi.protoField.tag(), "[B");
                        //rType = "Ljava/lang/Enum;";
                        visitGetFieldValue(mv, pfi, pojoName, pojoCodecName, 2, rType);
                        Label l0 = new Label();
                        mv.visitJumpInsn(IFNULL, l0);
                        visitGetFieldValue(mv, pfi, pojoName, pojoCodecName, 2, rType);
                        if (implInterface(pfi.field.getType(), ProtoBufEnum.class)) {
                            visitMethod(mv, INVOKEVIRTUAL, rType.substring(1, rType.length() - 1), "getValue", "()I", false);
                        } else {
                            visitMethod(mv, INVOKEVIRTUAL, rType.substring(1, rType.length() - 1), "ordinal", "()I", false);
                        }
                        mv.visitMethodInsn(INVOKESTATIC, "java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;", false);

                        Label l1 = new Label();
                        mv.visitJumpInsn(GOTO, l1);
                        mv.visitLabel(l0);
                        mv.visitFrame(Opcodes.F_FULL, 3, new Object[]{pojoCodecName, WRITER_NAME, pojoName}, 2,
                                new Object[]{WRITER_NAME, "[B"});
                        mv.visitInsn(ACONST_NULL);
                        mv.visitLabel(l1);
                        mv.visitFrame(Opcodes.F_FULL, 3, new Object[]{pojoCodecName, WRITER_NAME, pojoName}, 3,
                                new Object[]{WRITER_NAME, "[B", "java/lang/Integer"});

                        visitMethod(mv, INVOKEINTERFACE, WRITER_NAME, writeMethod, "([BLjava/lang/Integer;)V", true);
                    } else if (isRepeatedArray(pfi.field.getType())) {
                        visitArrayOpcodes(mv, pfi);
                    } else if (isList(pfi.field.getGenericType())) {
                        visitListOpcodes(mv, pfi);
                    } else if (isIterable(pfi.field.getGenericType())) {
                        visitIterableOpcodes(mv, pfi);
                    } else if (AsmUtil.isMap(pfi.field.getGenericType())) {
                        visitMapOpcodes(mv, pfi);
                    } else {
                        mv.visitVarInsn(ALOAD, 1);
                        mv.visitFieldInsn(GETSTATIC, pojoCodecName, "tag" + pfi.protoField.tag(), "[B");
                        rType = visitGetFieldValue(mv, pfi, pojoName, pojoCodecName, 2, rType);
                        if (writeMethod.equals("writeObject")) {
                            rType = "Ljava/lang/Object;";
                        }
                        if (pfi.field.getType().getName().equals("byte") ||
                                pfi.field.getType().getName().equals("short") ||
                                pfi.field.getType().getName().equals("char")) {
                            rType = "I";
                        } else if (pfi.field.getType().getName().equals("java.lang.Byte")) {
                            rType = "I";
                            mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Byte", "byteValue", "()B", false);
                        } else if (pfi.field.getType().getName().equals("java.lang.Short")) {
                            rType = "I";
                            mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Short", "shortValue", "()S", false);
                        } else if (pfi.field.getType().getName().equals("java.lang.Character")) {
                            rType = "I";
                            mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Character", "charValue", "()C", false);
                        }
                        visitMethod(mv, INVOKEINTERFACE, WRITER_NAME, writeMethod,
                                "([B" + rType + ")V", true);
                    }
                }
            }
            if (parentMapType != null) {
                visitSelfMapOpcodes(mv);
            }

            mv.visitLabel(l100);
            Label l300 = new Label();
            mv.visitJumpInsn(GOTO, l300);
            mv.visitLabel(l200);
            mv.visitFrame(Opcodes.F_SAME1, 0, null, 1, new Object[]{"java/lang/Exception"});
            mv.visitVarInsn(ASTORE, 3);
            mv.visitTypeInsn(NEW, ENCODE_EX_NAME);
            mv.visitInsn(DUP);
            mv.visitVarInsn(ALOAD, 3);
            mv.visitMethodInsn(INVOKESPECIAL, ENCODE_EX_NAME, "<init>", "(Ljava/lang/Exception;)V", false);
            mv.visitInsn(ATHROW);
            mv.visitLabel(l300);
            mv.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
        }
        mv.visitInsn(RETURN);
        mv.visitMaxs(4, 4);
        mv.visitEnd();
    }

    /**
     * 如果bean继承了Map，生成将自身的Map做序列化操作
     * @param mv
     */
    private void visitSelfMapOpcodes(MethodVisitor mv) {
        String rType = getDescriptor(parentMapType);
        String rClazzDesc;
        if (parentMapType instanceof ParameterizedType) {
            ParameterizedType ptype = (ParameterizedType)parentMapType;
            rClazzDesc = getDescriptor((Class)ptype.getRawType());
        } else if (parentMapType instanceof Class) {
            rClazzDesc = getDescriptor((Class)parentMapType);
        } else {
            int kindex = rType.indexOf("<");
            if (kindex == -1) {
                rClazzDesc = rType;
            } else {
                rClazzDesc = rType.substring(0, kindex);
                if (rClazzDesc.startsWith("L")) {
                    rClazzDesc += ";";
                }
            }
        }
        Class mapEntryCls = ProtoBufCodecRegister.INSTANCE.generateMapEntryClass(parentMapType, option, pojoCls);
        int index = mapMethods.indexOf(mapEntryCls.getName());
        String mapMethod;
        if (index < 0) {
            index = mapMethods.size();
            mapMethod = WRITE_MAP_PREFIX + index;
            //visitWriteMapMethod(mapMethod, mapEntryCls, pfi);
            visitWriteSelfMapFastMethod(mapMethod, mapEntryCls);
            mapMethods.add(mapEntryCls.getName());
        } else {
            mapMethod = WRITE_MAP_PREFIX + index;
        }

        mv.visitVarInsn(ALOAD, 0);
        mv.visitVarInsn(ALOAD, 1);
        visitTagOpcode(mv, WireFormat.RESERVED_TAG_VALUE_START);
        mv.visitVarInsn(ALOAD, 2);
        visitMethod(mv, INVOKESPECIAL, pojoCodecName, mapMethod,
                "(L" + WRITER_NAME + ";I" + rClazzDesc + ")V", false);
    }

    private String getMapEntryFieldName(String encodeName) {
        String mapEncoderName;
        int dotIndex = encodeName.lastIndexOf(".");
        String simpleName = encodeName.substring(dotIndex+1);
        mapEncoderName = simpleName.substring(0, 1).toLowerCase(Locale.ENGLISH) + simpleName.substring(1);

        return mapEncoderName;
    }

    private void visitMapOpcodes(MethodVisitor mv, ProtoFieldInfo pfi) {
        String entryEncoderName = buildMapEntryEncoderName(pfi.field.getGenericType(), null);
        String encodeName = getMapEntryFieldName(entryEncoderName);
        String rType = getDescriptor(pfi.field.getType());

        String mapDecoderName = buildMapDecoderName(pfi.field.getGenericType(), option);
        String simpleName     = getSimpleName(mapDecoderName);
        String fieldName      = lowerCaseFirstChar(simpleName);

        mv.visitVarInsn(ALOAD, 1);
        mv.visitFieldInsn(GETSTATIC, pojoCodecName, "tag" + pfi.protoField.tag(), "[B");
        visitMethodVisitIntValue(mv, pfi.protoField.tag());
        visitGetFieldValue(mv, pfi, pojoName, pojoCodecName, 2, rType);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitFieldInsn(GETFIELD, pojoCodecName, fieldName, "L" + MAP_ENTRY_ENCODER_NAME +";");
        mv.visitMethodInsn(INVOKEINTERFACE, WRITER_NAME, "writeMap",
                "([BILjava/util/Map;L" + MAP_ENTRY_ENCODER_NAME + ";)V", true);

    }

    private String getInnerArrayMethod(java.lang.reflect.Type type) {
        String typed = getDescriptor(type);
        int index = arrayMethods.indexOf(typed);
        if (index < 0) {
            index = arrayMethods.size();
        }
        return WRITE_ARRAY_PREFIX + index;
    }

    private void visitWriteSelfMapFastMethod(String methodName, Class mapEntryCls) {

        String mapName = toInternalName(parentMapType.getTypeName());
        String rType = getDescriptor(parentMapType);
        String rClazzDesc;
        if (parentMapType instanceof ParameterizedType) {
            ParameterizedType ptype = (ParameterizedType)parentMapType;
            rClazzDesc = getDescriptor((Class)ptype.getRawType());
        } else if (parentMapType instanceof Class) {
            rClazzDesc = getDescriptor((Class)parentMapType);
        } else {
            int kindex = rType.indexOf("<");
            if (kindex == -1) {
                rClazzDesc = rType;
            } else {
                rClazzDesc = rType.substring(0, kindex);
                if (rClazzDesc.startsWith("L")) {
                    rClazzDesc += ";";
                }
            }
        }

        String itemTypeName = toInternalName(mapEntryCls.getName());
        String itemTypeDesc = getDescriptor(mapEntryCls);

        java.lang.reflect.Type mapType = parentMapType;
        String keyTypeName = "java/lang/Object";
        String valTypeName = "java/lang/Object";
        if (mapType instanceof ParameterizedType) {
            ParameterizedType ptype = (ParameterizedType)mapType;
            if (ptype.getActualTypeArguments() != null
                    && ptype.getActualTypeArguments().length == 2) {
                keyTypeName = toInternalName(
                        getTypeName(ptype.getActualTypeArguments()[0]));
                valTypeName = toInternalName(
                        getTypeName(ptype.getActualTypeArguments()[1]));
            }
        }
        MethodVisitor mv;
        mv = cw.visitMethod(ACC_PRIVATE, methodName,
                "(L" + WRITER_NAME + ";I" + rClazzDesc + ")V",
                "(L" + WRITER_NAME + ";I" + rType + ")V", new String[] { ENCODE_EX_NAME });

        mv.visitCode();
        mv.visitVarInsn(ALOAD, 3);
        Label lbNull = new Label();
        mv.visitJumpInsn(IFNULL, lbNull);

        mv.visitVarInsn(ALOAD, 3);
        visitMethod(mv, INVOKEINTERFACE, "java/util/Map", "isEmpty", "()Z", true);
        Label lbNotEmpty = new Label();
        mv.visitJumpInsn(IFEQ, lbNotEmpty);

        mv.visitLabel(lbNull);
        mv.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
        mv.visitInsn(RETURN);
        mv.visitLabel(lbNotEmpty);
        mv.visitFrame(Opcodes.F_SAME, 0, null, 0, null);

        mv.visitTypeInsn(NEW, itemTypeName);
        mv.visitInsn(DUP);
        visitMethod(mv, INVOKESPECIAL, itemTypeName, "<init>", "()V", false);
        mv.visitVarInsn(ASTORE, 4);
        mv.visitFieldInsn(GETSTATIC, REGISTER_NAME,
                "INSTANCE", "L" + REGISTER_NAME + ";");
        mv.visitLdcInsn(org.objectweb.asm.Type.getType(itemTypeDesc));
        mv.visitVarInsn(ALOAD, 0);
        visitMethod(mv, INVOKEVIRTUAL, pojoCodecName,
                "getProtoBufOption", "()L" + PROTOBUF_OPTIION_NAME + ";",
                false);
        visitMethod(mv, INVOKEVIRTUAL, REGISTER_NAME,
                "getEncoder", "(Ljava/lang/Class;L" + PROTOBUF_OPTIION_NAME + ";)L" + IFACE_NAME + ";",
                false);
        mv.visitVarInsn(ASTORE, 5);
        mv.visitVarInsn(ALOAD, 3);
        visitMethod(mv, INVOKEINTERFACE, "java/util/Map", "entrySet",
                "()Ljava/util/Set;", true);
        visitMethod(mv, INVOKEINTERFACE, "java/util/Set", "iterator",
                "()Ljava/util/Iterator;", true);
        mv.visitVarInsn(ASTORE, 6);
        Label l0 = new Label();
        mv.visitLabel(l0);
        mv.visitFrame(F_APPEND,3, new Object[] {
                itemTypeName, IFACE_NAME, "java/util/Iterator"}, 0, null);
        mv.visitVarInsn(ALOAD, 6);
        visitMethod(mv, INVOKEINTERFACE, "java/util/Iterator", "hasNext",
                "()Z", true);
        Label l1 = new Label();
        mv.visitJumpInsn(IFEQ, l1);
        mv.visitVarInsn(ALOAD, 6);
        visitMethod(mv, INVOKEINTERFACE, "java/util/Iterator", "next",
                "()Ljava/lang/Object;", true);
        mv.visitTypeInsn(CHECKCAST, "java/util/Map$Entry");
        mv.visitVarInsn(ASTORE, 7);
        mv.visitVarInsn(ALOAD, 4);
        mv.visitVarInsn(ALOAD, 7);
        visitMethod(mv, INVOKEINTERFACE, "java/util/Map$Entry", "getKey",
                "()Ljava/lang/Object;", true);
        if (!"java/lang/Object".equals(keyTypeName)) {
            mv.visitTypeInsn(CHECKCAST, keyTypeName);
        }
        mv.visitFieldInsn(PUTFIELD, itemTypeName, "key", "L" + keyTypeName + ";");
        mv.visitVarInsn(ALOAD, 4);
        mv.visitVarInsn(ALOAD, 7);
        visitMethod(mv, INVOKEINTERFACE, "java/util/Map$Entry", "getValue",
                "()Ljava/lang/Object;", true);
        if (!"java/lang/Object".equals(valTypeName)) {
            mv.visitTypeInsn(CHECKCAST, valTypeName);
        }
        mv.visitFieldInsn(PUTFIELD, itemTypeName, "value", "L" + valTypeName + ";");
        mv.visitVarInsn(ALOAD, 1);
        mv.visitFieldInsn(GETSTATIC, pojoCodecName, "tag" + WireFormat.RESERVED_TAG_VALUE_START, "[B");
        mv.visitVarInsn(ILOAD, 2);
        mv.visitVarInsn(ALOAD, 4);
        mv.visitVarInsn(ALOAD, 5);
        visitMethod(mv, INVOKEINTERFACE, WRITER_NAME, "writeMessage",
                "([BILjava/lang/Object;L" + IFACE_NAME + ";)V", true);
        mv.visitJumpInsn(GOTO, l0);
        mv.visitLabel(l1);
        mv.visitFrame(F_CHOP,1, null, 0, null);
        mv.visitInsn(RETURN);
        mv.visitMaxs(5, 8);
        mv.visitEnd();
    }

    private void visitArrayOpcodes(MethodVisitor mv, ProtoFieldInfo pfi) {
        String arrayMethod = getInnerArrayMethod(pfi.field.getGenericType());
        String rType = getDescriptor(pfi.field.getType());
        java.lang.reflect.Type itemType = null;
        if (pfi.field.getGenericType() instanceof GenericArrayType) {
            java.lang.reflect.Type type = ((GenericArrayType)pfi.field.getGenericType()).getGenericComponentType();
            if (type instanceof ParameterizedType) {
                ParameterizedType pType = (ParameterizedType)type;
                itemType = (Class)pType.getRawType();
            }

        } else {
            itemType = ((Class) pfi.field.getGenericType())
                    .getComponentType();
        }

        if (isPojo(itemType)) {
            String codecName = getPojoEncoderName(itemType);
            //String listDescriptor = getDescriptor(pfi.field.getType());
            String listDescriptor = "[Ljava/lang/Object;";
            mv.visitVarInsn(ALOAD, 1);
            mv.visitFieldInsn(GETSTATIC, pojoCodecName, "tag" + pfi.protoField.tag(), "[B");
            visitTagOpcode(mv, pfi.protoField.tag());
            rType = visitGetFieldValue(mv, pfi, pojoName, pojoCodecName, 2, rType);
            mv.visitVarInsn(ALOAD, 0);
//            mv.visitFieldInsn(GETFIELD, pojoCodecName, codecName,
//                    "L" + IFACE_NAME +  ";");
            String itemCodecName = getPojoEncoderName(itemType);
            String getEncoderName = "get" + itemCodecName.substring(0, 1).toUpperCase(Locale.ENGLISH) + itemCodecName.substring(1);
            mv.visitMethodInsn(INVOKESPECIAL, pojoCodecName, getEncoderName, "()L" + IFACE_NAME + ";", false);
            mv.visitMethodInsn(INVOKEINTERFACE, WRITER_NAME, "writeMessages",
                    "([BI[Ljava/lang/Object;L" + IFACE_NAME + ";)V", true);
            return;
        } else if (pfi.protoField.type() == Type.BOOL) {
            mv.visitVarInsn(ALOAD, 0);
            mv.visitVarInsn(ALOAD, 1);
            mv.visitFieldInsn(GETSTATIC, pojoCodecName, "tag" + pfi.protoField.tag(), "[B");
            rType = visitGetFieldValue(mv, pfi, pojoName, pojoCodecName, 2, rType);
            visitMethod(mv, INVOKESPECIAL, pojoCodecName, "writeArrayBoolean",
                    "(L" + WRITER_NAME + ";[B" + rType + ")V", false);
            return;
        } else if (pfi.protoField.type() == Type.INT32
                || pfi.protoField.type() == Type.SINT32
                || pfi.protoField.type() == Type.UINT32
                || pfi.protoField.type() == Type.FIXED32
                || pfi.protoField.type() == Type.SFIXED32) {
            mv.visitVarInsn(ALOAD, 0);
            mv.visitVarInsn(ALOAD, 1);
            mv.visitFieldInsn(GETSTATIC, pojoCodecName, "tag" + pfi.protoField.tag(), "[B");
            rType = visitGetFieldValue(mv, pfi, pojoName, pojoCodecName, 2, rType);
            mv.visitFieldInsn(GETSTATIC, FIELD_TYPE_NAME, pfi.protoField.type().name(), "L" + FIELD_TYPE_NAME + ";");
            visitMethod(mv, INVOKESPECIAL, pojoCodecName, "writeArrayInt",
                    "(L" + WRITER_NAME + ";[B" + rType + "L" + FIELD_TYPE_NAME + ";)V", false);
            return;
        } else if (pfi.protoField.type() == Type.ENUM) {
            mv.visitVarInsn(ALOAD, 0);
            mv.visitVarInsn(ALOAD, 1);
            mv.visitFieldInsn(GETSTATIC, pojoCodecName, "tag" + pfi.protoField.tag(), "[B");
            rType = visitGetFieldValue(mv, pfi, pojoName, pojoCodecName, 2, rType);
            visitMethod(mv, INVOKESPECIAL, pojoCodecName, "writeArrayEnum",
                    "(L" + WRITER_NAME + ";[B[Ljava/lang/Enum;)V", false);
            return;
        } else if (pfi.protoField.type() == Type.INT64
                || pfi.protoField.type() == Type.SINT64
                || pfi.protoField.type() == Type.UINT64
                || pfi.protoField.type() == Type.FIXED64
                || pfi.protoField.type() == Type.SFIXED64) {
            mv.visitVarInsn(ALOAD, 0);
            mv.visitVarInsn(ALOAD, 1);
            mv.visitFieldInsn(GETSTATIC, pojoCodecName, "tag" + pfi.protoField.tag(), "[B");
            rType = visitGetFieldValue(mv, pfi, pojoName, pojoCodecName, 2, rType);
            mv.visitFieldInsn(GETSTATIC, FIELD_TYPE_NAME, pfi.protoField.type().name(), "L" + FIELD_TYPE_NAME + ";");
            visitMethod(mv, INVOKESPECIAL, pojoCodecName, "writeArrayLong",
                    "(L" + WRITER_NAME + ";[B" + rType + "L" + FIELD_TYPE_NAME + ";)V", false);
            return;
        } else if (pfi.protoField.type() == Type.FLOAT) {
            mv.visitVarInsn(ALOAD, 0);
            mv.visitVarInsn(ALOAD, 1);
            mv.visitFieldInsn(GETSTATIC, pojoCodecName, "tag" + pfi.protoField.tag(), "[B");
            rType = visitGetFieldValue(mv, pfi, pojoName, pojoCodecName, 2, rType);
            visitMethod(mv, INVOKESPECIAL, pojoCodecName, "writeArrayFloat",
                    "(L" + WRITER_NAME + ";[B" + rType + ")V", false);
            return;
        } else if (pfi.protoField.type() == Type.DOUBLE) {
            mv.visitVarInsn(ALOAD, 0);
            mv.visitVarInsn(ALOAD, 1);
            mv.visitFieldInsn(GETSTATIC, pojoCodecName, "tag" + pfi.protoField.tag(), "[B");
            rType = visitGetFieldValue(mv, pfi, pojoName, pojoCodecName, 2, rType);
            visitMethod(mv, INVOKESPECIAL, pojoCodecName, "writeArrayDouble",
                    "(L" + WRITER_NAME + ";[B" + rType + ")V", false);
            return;
        }
        mv.visitVarInsn(ALOAD, 0);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitFieldInsn(GETSTATIC, pojoCodecName, "tag" + pfi.protoField.tag(), "[B");
        rType = visitGetFieldValue(mv, pfi, pojoName, pojoCodecName, 2, rType);
        visitMethod(mv, INVOKESPECIAL, pojoCodecName, arrayMethod,
                "(L" + WRITER_NAME + ";[B" + rType + ")V", false);
        visitInnerArrayMethod(pfi.field.getGenericType(), pfi);
    }

    private void visitListOpcodes(MethodVisitor mv, ProtoFieldInfo pfi) {
        java.lang.reflect.Type itemType = Object.class;
        if (pfi.field.getGenericType() instanceof ParameterizedType) {
            ParameterizedType ptype = (ParameterizedType)pfi.field.getGenericType();
            java.lang.reflect.Type[] ts = ptype.getActualTypeArguments();
            if (ts != null && ts.length > 0) {
                itemType = ts[0];
            }
        }
        String rType = getDescriptor(pfi.field.getType());
        if (isPojo(itemType)) {
            String codecName = getPojoEncoderName(itemType);
            mv.visitVarInsn(ALOAD, 1);
            mv.visitFieldInsn(GETSTATIC, pojoCodecName, "tag" + pfi.protoField.tag(), "[B");
            visitTagOpcode(mv, pfi.protoField.tag());
            rType = visitGetFieldValue(mv, pfi, pojoName, pojoCodecName, 2, rType);
            mv.visitVarInsn(ALOAD, 0);
            String listDescriptor = getDescriptor(pfi.field.getType());
            String encoderName = toInternalName(getEncoderName((Class)itemType, option));
            if (!encoderName.equals(pojoCodecName)) {
//                mv.visitFieldInsn(GETFIELD, pojoCodecName, codecName,
//                        "L" + IFACE_NAME + ";");
                String itemCodecName = getPojoEncoderName(itemType);
                String getEncoderName = "get" + itemCodecName.substring(0, 1).toUpperCase(Locale.ENGLISH) + itemCodecName.substring(1);
                mv.visitMethodInsn(INVOKESPECIAL, pojoCodecName, getEncoderName, "()L" + IFACE_NAME + ";", false);
            }
            visitMethod(mv, INVOKEINTERFACE, WRITER_NAME, "writeMessages",
                    "([BI" + listDescriptor
                            + "L" + IFACE_NAME + ";)V",
                    true);
            return;
        } else if (pfi.protoField.type() == Type.BOOL) {
            mv.visitVarInsn(ALOAD, 1);
            mv.visitFieldInsn(GETSTATIC, pojoCodecName, "tag" + pfi.protoField.tag(), "[B");
            rType = visitGetFieldValue(mv, pfi, pojoName, pojoCodecName, 2, rType);
            visitMethod(mv, INVOKEINTERFACE, WRITER_NAME, "writePackedBools",
                    "([B" + rType + ")V",
                    true);
            return;
        } else if (pfi.protoField.type() == Type.INT32
                || pfi.protoField.type() == Type.SINT32
                || pfi.protoField.type() == Type.UINT32
                || pfi.protoField.type() == Type.FIXED32
                || pfi.protoField.type() == Type.SFIXED32) {
            mv.visitVarInsn(ALOAD, 1);
            mv.visitFieldInsn(GETSTATIC, pojoCodecName, "tag" + pfi.protoField.tag(), "[B");
            rType = visitGetFieldValue(mv, pfi, pojoName, pojoCodecName, 2, rType);
//            visitMethod(mv, INVOKESPECIAL, pojoCodecName, "writeListInt",
//                    "(L" + WRITER_NAME + ";[B" + rType + ")V", false);

            mv.visitFieldInsn(GETSTATIC, FIELD_TYPE_NAME, pfi.protoField.type().name(), "L" + FIELD_TYPE_NAME + ";");
            visitMethod(mv, INVOKEINTERFACE, WRITER_NAME, "writePackedInts",
                    "([B" + rType
                            + "L" + FIELD_TYPE_NAME + ";)V",
                    true);
            return;
        } else if (pfi.protoField.type() == Type.ENUM) {
            mv.visitVarInsn(ALOAD, 1);
            mv.visitFieldInsn(GETSTATIC, pojoCodecName, "tag" + pfi.protoField.tag(), "[B");
            rType = visitGetFieldValue(mv, pfi, pojoName, pojoCodecName, 2, rType);
            if (!implInterface((Class)itemType, ProtoBufEnum.class)) {
                visitMethod(mv, INVOKEINTERFACE, WRITER_NAME, "writeListEnum",
                        "([B" + rType + ")V",
                        true);
            } else {
                visitMethod(mv, INVOKEINTERFACE, WRITER_NAME, "writeListProtoEnum",
                        "([B" + rType + ")V",
                        true);
            }
            return;
        } else if (pfi.protoField.type() == Type.INT64
                || pfi.protoField.type() == Type.UINT64
                || pfi.protoField.type() == Type.SINT64
                || pfi.protoField.type() == Type.FIXED64
                || pfi.protoField.type() == Type.SFIXED64) {
            mv.visitVarInsn(ALOAD, 1);
            mv.visitFieldInsn(GETSTATIC, pojoCodecName, "tag" + pfi.protoField.tag(), "[B");
            rType = visitGetFieldValue(mv, pfi, pojoName, pojoCodecName, 2, rType);
            mv.visitFieldInsn(GETSTATIC, FIELD_TYPE_NAME, pfi.protoField.type().name(), "L" + FIELD_TYPE_NAME + ";");
            visitMethod(mv, INVOKEINTERFACE, WRITER_NAME, "writePackedLongs",
                    "([B" + rType
                            + "L" + FIELD_TYPE_NAME + ";)V",
                    true);
            return;
        } else if (pfi.protoField.type() == Type.DOUBLE) {
            mv.visitVarInsn(ALOAD, 1);
            mv.visitFieldInsn(GETSTATIC, pojoCodecName, "tag" + pfi.protoField.tag(), "[B");
            rType = visitGetFieldValue(mv, pfi, pojoName, pojoCodecName, 2, rType);
            visitMethod(mv, INVOKEINTERFACE, WRITER_NAME, "writePackedDoubles",
                    "([B" + rType + ")V",
                    true);
            return;
        } else if (pfi.protoField.type() == Type.FLOAT) {
            mv.visitVarInsn(ALOAD, 1);
            mv.visitFieldInsn(GETSTATIC, pojoCodecName, "tag" + pfi.protoField.tag(), "[B");
            rType = visitGetFieldValue(mv, pfi, pojoName, pojoCodecName, 2, rType);
            visitMethod(mv, INVOKEINTERFACE, WRITER_NAME, "writePackedFloats",
                    "([B" + rType + ")V",
                    true);
            return;
        } else if (isMap(itemType)) {
            String mapDecoderName = buildMapDecoderName(itemType, option);
            String simpleName     = getSimpleName(mapDecoderName);
            String fieldName      = lowerCaseFirstChar(simpleName);
            mv.visitVarInsn(ALOAD, 2);
            rType = visitGetFieldValue(mv, pfi, pojoName, pojoCodecName, 2, rType);
            mv.visitMethodInsn(INVOKESTATIC, "io/edap/util/CollectionUtils", "isEmpty",
                    "(Ljava/util/Collection;)Z", false);
            Label lbNotEmpty = new Label();
            mv.visitJumpInsn(IFEQ, lbNotEmpty);
            mv.visitInsn(RETURN);

            String encodeName = buildMapEntryEncoderName(itemType, option);
            //MapEntryTypeInfo mti = getMapEntryTypeInfo(itemType);
            String mapEncoderName = getMapEntryFieldName(encodeName);

            int varMapEncoder = 3;
            mv.visitLabel(lbNotEmpty);
            // 为MapEntryEncoder赋值
            mv.visitVarInsn(ALOAD, 0);
            mv.visitFieldInsn(GETFIELD, pojoCodecName, fieldName, "L" + MAP_ENTRY_ENCODER_NAME +";");
            mv.visitVarInsn(ASTORE, varMapEncoder);

            // list属性值赋值为新的变量
            int varlist = varMapEncoder + 1;
            mv.visitVarInsn(ALOAD, 2);
            visitGetFieldValue(mv, pfi, pojoName, pojoCodecName, 2, rType);
            mv.visitVarInsn(ASTORE, varlist);

            // for循环编码Map
            int varForIndex = varlist + 1;
            mv.visitInsn(ICONST_0);
            mv.visitVarInsn(ISTORE, varForIndex);

            Label lbFor = new Label();
            mv.visitLabel(lbFor);
            mv.visitVarInsn(ILOAD, varForIndex);
            mv.visitVarInsn(ALOAD, varlist);
            mv.visitMethodInsn(INVOKEINTERFACE, "java/util/List", "size", "()I", true);

            Label lbForEnd = new Label();
            // 编码Map
            mv.visitJumpInsn(IF_ICMPGE, lbForEnd);
            mv.visitVarInsn(ALOAD, 1);
            mv.visitFieldInsn(GETSTATIC, pojoCodecName, "tag" + pfi.protoField.tag(), "[B");
            visitTagOpcode(mv, pfi.protoField.tag());
            mv.visitVarInsn(ALOAD, varlist);
            mv.visitVarInsn(ILOAD, varForIndex);
            mv.visitMethodInsn(INVOKEINTERFACE, "java/util/List", "get",
                    "(I)Ljava/lang/Object;", true);
            mv.visitTypeInsn(CHECKCAST, "java/util/Map");
            mv.visitVarInsn(ALOAD, varMapEncoder);
            mv.visitMethodInsn(INVOKEINTERFACE, WRITER_NAME, "writeMapMessage",
                    "([BILjava/util/Map;L" + MAP_ENTRY_ENCODER_NAME + ";)V", true);
            mv.visitIincInsn(varForIndex, 1);
            mv.visitJumpInsn(GOTO, lbFor);
            mv.visitLabel(lbForEnd);

            mv.visitInsn(RETURN);

            return;

        } else if (itemType instanceof ParameterizedType) {
            mv.visitVarInsn(ALOAD, 1);
            mv.visitFieldInsn(GETSTATIC, pojoCodecName, "tag" + pfi.protoField.tag(), "[B");
            rType = visitGetFieldValue(mv, pfi, pojoName, pojoCodecName, 2, rType);
            visitMethod(mv, INVOKEINTERFACE, WRITER_NAME, "writeObject",
                    "([BLjava/lang/Object;)V",
                    true);
            return;
        }
        mv.visitVarInsn(ALOAD, 0);
        mv.visitVarInsn(ALOAD, 1);
        rType = visitGetFieldValue(mv, pfi, pojoName, pojoCodecName, 2, rType);
        String listMethod = getInnerListMethod(pfi.field.getGenericType());
        visitMethod(mv, INVOKESPECIAL, pojoCodecName, listMethod,
                "(L" + WRITER_NAME + ";" + rType + ")V", false);
        visitInnerListMethod(pfi.field.getGenericType(), pfi);
    }

    private void visitIterableOpcodes(MethodVisitor mv, ProtoFieldInfo pfi) {
        java.lang.reflect.Type itemType = Object.class;
        if (pfi.field.getGenericType() instanceof ParameterizedType) {
            ParameterizedType ptype = (ParameterizedType)pfi.field.getGenericType();
            java.lang.reflect.Type[] ts = ptype.getActualTypeArguments();
            if (ts != null && ts.length > 0) {
                itemType = ts[0];
            }
        }
        String rType = getDescriptor(pfi.field.getType());
        if (isPojo(itemType)) {
            String codecName = getPojoEncoderName(itemType);
            mv.visitVarInsn(ALOAD, 1);
            mv.visitFieldInsn(GETSTATIC, pojoCodecName, "tag" + pfi.protoField.tag(), "[B");
            visitTagOpcode(mv, pfi.protoField.tag());
            rType = visitGetFieldValue(mv, pfi, pojoName, pojoCodecName, 2, rType);
            mv.visitVarInsn(ALOAD, 0);
            String listDescriptor = getDescriptor(pfi.field.getType());
            String encoderName = toInternalName(getEncoderName((Class)itemType, option));
            if (!encoderName.equals(pojoCodecName)) {
//                mv.visitFieldInsn(GETFIELD, pojoCodecName, codecName,
//                        "L" + IFACE_NAME + ";");
                String itemCodecName = getPojoEncoderName(itemType);
                String getEncoderName = "get" + itemCodecName.substring(0, 1).toUpperCase(Locale.ENGLISH) + itemCodecName.substring(1);
                mv.visitMethodInsn(INVOKESPECIAL, pojoCodecName, getEncoderName, "()L" + IFACE_NAME + ";", false);
            }
            visitMethod(mv, INVOKEINTERFACE, WRITER_NAME, "writeMessages",
                    "([BI" + listDescriptor
                            + "L" + IFACE_NAME + ";)V",
                    true);
            return;
        } else if (pfi.protoField.type() == Type.BOOL) {
            mv.visitVarInsn(ALOAD, 1);
            mv.visitFieldInsn(GETSTATIC, pojoCodecName, "tag" + pfi.protoField.tag(), "[B");
            rType = visitGetFieldValue(mv, pfi, pojoName, pojoCodecName, 2, rType);
            visitMethod(mv, INVOKEINTERFACE, WRITER_NAME, "writePackedBools",
                    "([B" + rType + ")V",
                    true);
            return;
        } else if (pfi.protoField.type() == Type.INT32
                || pfi.protoField.type() == Type.SINT32
                || pfi.protoField.type() == Type.UINT32
                || pfi.protoField.type() == Type.FIXED32
                || pfi.protoField.type() == Type.SFIXED32) {
            mv.visitVarInsn(ALOAD, 1);
            mv.visitFieldInsn(GETSTATIC, pojoCodecName, "tag" + pfi.protoField.tag(), "[B");
            rType = visitGetFieldValue(mv, pfi, pojoName, pojoCodecName, 2, rType);
//            visitMethod(mv, INVOKESPECIAL, pojoCodecName, "writeListInt",
//                    "(L" + WRITER_NAME + ";[B" + rType + ")V", false);

            mv.visitFieldInsn(GETSTATIC, FIELD_TYPE_NAME, pfi.protoField.type().name(), "L" + FIELD_TYPE_NAME + ";");
            rType = "Ljava/lang/Iterable;";
            visitMethod(mv, INVOKEINTERFACE, WRITER_NAME, "writePackedInts",
                    "([B" + rType
                            + "L" + FIELD_TYPE_NAME + ";)V",
                    true);
            return;
        } else if (pfi.protoField.type() == Type.ENUM) {
            mv.visitVarInsn(ALOAD, 1);
            mv.visitFieldInsn(GETSTATIC, pojoCodecName, "tag" + pfi.protoField.tag(), "[B");
            rType = visitGetFieldValue(mv, pfi, pojoName, pojoCodecName, 2, rType);
            if (!implInterface((Class)itemType, ProtoBufEnum.class)) {
                visitMethod(mv, INVOKEINTERFACE, WRITER_NAME, "writeListEnum",
                        "([B" + rType + ")V",
                        true);
            } else {
                visitMethod(mv, INVOKEINTERFACE, WRITER_NAME, "writeListProtoEnum",
                        "([B" + rType + ")V",
                        true);
            }
            return;
        } else if (pfi.protoField.type() == Type.INT64
                || pfi.protoField.type() == Type.UINT64
                || pfi.protoField.type() == Type.SINT64
                || pfi.protoField.type() == Type.FIXED64
                || pfi.protoField.type() == Type.SFIXED64) {
            mv.visitVarInsn(ALOAD, 1);
            mv.visitFieldInsn(GETSTATIC, pojoCodecName, "tag" + pfi.protoField.tag(), "[B");
            rType = visitGetFieldValue(mv, pfi, pojoName, pojoCodecName, 2, rType);
            mv.visitFieldInsn(GETSTATIC, FIELD_TYPE_NAME, pfi.protoField.type().name(), "L" + FIELD_TYPE_NAME + ";");
            visitMethod(mv, INVOKEINTERFACE, WRITER_NAME, "writePackedLongs",
                    "([B" + rType
                            + "L" + FIELD_TYPE_NAME + ";)V",
                    true);
            return;
        } else if (pfi.protoField.type() == Type.DOUBLE) {
            mv.visitVarInsn(ALOAD, 1);
            mv.visitFieldInsn(GETSTATIC, pojoCodecName, "tag" + pfi.protoField.tag(), "[B");
            rType = visitGetFieldValue(mv, pfi, pojoName, pojoCodecName, 2, rType);
            visitMethod(mv, INVOKEINTERFACE, WRITER_NAME, "writePackedDoubles",
                    "([B" + rType + ")V",
                    true);
            return;
        } else if (pfi.protoField.type() == Type.FLOAT) {
            mv.visitVarInsn(ALOAD, 1);
            mv.visitFieldInsn(GETSTATIC, pojoCodecName, "tag" + pfi.protoField.tag(), "[B");
            rType = visitGetFieldValue(mv, pfi, pojoName, pojoCodecName, 2, rType);
            visitMethod(mv, INVOKEINTERFACE, WRITER_NAME, "writePackedFloats",
                    "([B" + rType + ")V",
                    true);
            return;
        }
        mv.visitVarInsn(ALOAD, 0);
        mv.visitVarInsn(ALOAD, 1);
        rType = visitGetFieldValue(mv, pfi, pojoName, pojoCodecName, 2, rType);
        String listMethod = getInnerIteratorMethod(pfi.field.getGenericType());
        rType = "Ljava/lang/Iterable;";
        visitMethod(mv, INVOKESPECIAL, pojoCodecName, listMethod,
                "(L" + WRITER_NAME + ";" + rType + ")V", false);
        visitInnerIteratorMethod(pfi.field.getGenericType(), pfi);
    }

    private String getInnerListMethod(java.lang.reflect.Type type) {
        String typed = getDescriptor(type);
        int index = listMethods.indexOf(typed);
        if (index < 0) {
            index = listMethods.size();
        }
        return WRITE_LIST_PREFIX + index;
    }

    private void visitInnerListMethod(java.lang.reflect.Type type,
                                      ProtoFieldInfo pfi) {
        String typed = getDescriptor(type);
        if (listMethods.indexOf(typed) >= 0) {
            return;
        }
        String typeName;
        String typeDescriptor;
        String itemName = toInternalName(Object.class.getName());
        String itemDesc = "L" + itemName + ";";
        if (type instanceof ParameterizedType) {
            ParameterizedType ptype = (ParameterizedType)type;
            typeName = toInternalName(((Class)ptype.getRawType()).getName());
            if (ptype.getActualTypeArguments() != null &&
                    ptype.getActualTypeArguments().length > 0) {
                java.lang.reflect.Type itemType = ptype.getActualTypeArguments()[0];
                itemDesc = getDescriptor(itemType);
                if (pfi.protoField.type() != Type.OBJECT) {
                    itemName = toInternalName(((Class) itemType).getName());
                }
            }
        } else {
            typeName = toInternalName(((Class)type).getName());
        }
        typeDescriptor = getDescriptor(type);
        String name = getInnerListMethod(type);
        MethodVisitor mv;
        mv = cw.visitMethod(ACC_PRIVATE, name,
                "(L" + WRITER_NAME + ";L" + typeName + ";)V",
                "(L" + WRITER_NAME + ";" + typeDescriptor + ")V", new String[] { ENCODE_EX_NAME });
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 2);
        visitMethod(mv, INVOKESTATIC, COLLECTION_UTIL,
                "isEmpty", "(Ljava/util/Collection;)Z", false);
        Label l0 = new Label();
        mv.visitJumpInsn(IFEQ, l0);
        mv.visitInsn(RETURN);
        mv.visitLabel(l0);
        mv.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
        mv.visitVarInsn(ALOAD, 2);
        Label l1, l2;

        visitMethod(mv, INVOKEINTERFACE, "java/util/List", "size", "()I", true);
        mv.visitVarInsn(ISTORE, 3);
        mv.visitInsn(ICONST_0);
        mv.visitVarInsn(ISTORE, 4);
        l1 = new Label();
        mv.visitLabel(l1);
        mv.visitFrame(F_APPEND, 2, new Object[]{INTEGER, INTEGER}, 0, null);
        mv.visitVarInsn(ILOAD, 4);
        mv.visitVarInsn(ILOAD, 3);
        l2 = new Label();
        mv.visitJumpInsn(IF_ICMPGE, l2);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitFieldInsn(GETSTATIC, pojoCodecName, "tag" + pfi.protoField.tag(), "[B");
        mv.visitVarInsn(ALOAD, 2);
        mv.visitVarInsn(ILOAD, 4);
        visitMethod(mv, INVOKEINTERFACE, "java/util/List", "get",
                "(I)Ljava/lang/Object;", true);
        mv.visitTypeInsn(CHECKCAST, itemName);

        String writeMethod = getWriteMethod(pfi.protoField.type());

        visitMethod(mv, INVOKEINTERFACE, WRITER_NAME, writeMethod,
                "([B" + itemDesc + ")V",
                true);

        mv.visitIincInsn(4, 1);

        mv.visitJumpInsn(GOTO, l1);
        mv.visitLabel(l2);
        mv.visitFrame(Opcodes.F_CHOP,1, null, 0, null);
        mv.visitInsn(RETURN);
        mv.visitMaxs(5, 6);
        mv.visitEnd();

        listMethods.add(name);
    }

    private String getInnerIteratorMethod(java.lang.reflect.Type type) {
        String typed = getDescriptor(type);
        int index = iteratorMethods.indexOf(typed);
        if (index < 0) {
            index = iteratorMethods.size();
            //iteratorMethods.add(typed);
        }
        return WRITE_ITERATOR_PREFIX + index;
    }

    private void visitInnerIteratorMethod(java.lang.reflect.Type type,
                                          ProtoFieldInfo pfi) {
        String typed = getDescriptor(type);
        if (iteratorMethods.indexOf(typed) >= 0) {
            return;
        }

        String typeName;
        String typeDescriptor;
        String itemName = toInternalName(Object.class.getName());
        String itemDesc = "L" + itemName + ";";
        if (type instanceof ParameterizedType) {
            ParameterizedType ptype = (ParameterizedType)type;
            typeName = toInternalName(((Class)ptype.getRawType()).getName());
            if (ptype.getActualTypeArguments() != null &&
                    ptype.getActualTypeArguments().length > 0) {
                java.lang.reflect.Type itemType = ptype.getActualTypeArguments()[0];
                itemDesc = getDescriptor(itemType);
                if (pfi.protoField.type() != Type.OBJECT) {
                    itemName = toInternalName(((Class) itemType).getName());
                }
            }
        } else {
            typeName = toInternalName(((Class)type).getName());
        }
        typeDescriptor = getDescriptor(type);
        String name = getInnerIteratorMethod(type);

        MethodVisitor mv;
        mv = cw.visitMethod(ACC_PRIVATE, name, "(L" + WRITER_NAME + ";Ljava/lang/Iterable;)V",
                "(L" + WRITER_NAME + ";Ljava/lang/Iterable<" + itemDesc + ">;)V", null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 2);
        mv.visitMethodInsn(INVOKEINTERFACE, "java/lang/Iterable", "iterator", "()Ljava/util/Iterator;", true);
        mv.visitVarInsn(ASTORE, 3);
        Label l0 = new Label();
        mv.visitLabel(l0);
        mv.visitFrame(Opcodes.F_APPEND,1, new Object[] {"java/util/Iterator"}, 0, null);
        mv.visitVarInsn(ALOAD, 3);
        mv.visitMethodInsn(INVOKEINTERFACE, "java/util/Iterator", "hasNext", "()Z", true);
        Label l1 = new Label();
        mv.visitJumpInsn(IFEQ, l1);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitFieldInsn(GETSTATIC, pojoCodecName, "tag" + pfi.protoField.tag(), "[B");
        mv.visitVarInsn(ALOAD, 3);
        mv.visitMethodInsn(INVOKEINTERFACE, "java/util/Iterator", "next", "()Ljava/lang/Object;", true);
        mv.visitTypeInsn(CHECKCAST, itemName);
        String writeMethod = getWriteMethod(pfi.protoField.type());
        if ("writeObject".equals(writeMethod)) {
            itemDesc = "Ljava/lang/Object;";
        }
        mv.visitMethodInsn(INVOKEINTERFACE, WRITER_NAME, writeMethod, "([B" + itemDesc + ")V", true);
        mv.visitJumpInsn(GOTO, l0);
        mv.visitLabel(l1);
        mv.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
        mv.visitInsn(RETURN);
        mv.visitMaxs(3, 5);
        mv.visitEnd();

        iteratorMethods.add(typed);
    }

    private void visitInnerArrayMethod(java.lang.reflect.Type type,
                                       ProtoFieldInfo pfi) {
        String typed = getDescriptor(type);
        if (arrayMethods.indexOf(typed) >= 0) {
            return;
        }

        java.lang.reflect.Type itemType = null;
        if (pfi.field.getGenericType() instanceof GenericArrayType) {
            java.lang.reflect.Type ttype = ((GenericArrayType)pfi.field.getGenericType()).getGenericComponentType();
            if (ttype instanceof ParameterizedType) {
                ParameterizedType pType = (ParameterizedType)ttype;
                itemType = (Class)pType.getRawType();
            }

        } else {
            itemType = ((Class) pfi.field.getGenericType())
                    .getComponentType();
        }
        String itemName = getDescriptor(itemType);
        String arrayMethod = getInnerArrayMethod(type);
        MethodVisitor mv;
        mv = cw.visitMethod(ACC_PRIVATE, arrayMethod,
                "(L" + WRITER_NAME + ";[B[" + itemName + ")V", null,
                new String[] { ENCODE_EX_NAME });
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 3);
        Label l0 = new Label();
        mv.visitJumpInsn(IFNULL, l0);
        mv.visitVarInsn(ALOAD, 3);
        mv.visitInsn(ARRAYLENGTH);
        Label l1 = new Label();
        mv.visitJumpInsn(IFNE, l1);
        mv.visitLabel(l0);
        mv.visitFrame(F_SAME, 0, null, 0, null);
        mv.visitInsn(RETURN);
        mv.visitLabel(l1);
        mv.visitFrame(F_SAME, 0, null, 0, null);
        mv.visitVarInsn(ALOAD, 3);
        mv.visitInsn(ARRAYLENGTH);
        mv.visitVarInsn(ISTORE, 4);
        Label l2, l3;
        mv.visitInsn(ICONST_0);
        mv.visitVarInsn(ISTORE, 5);
        l2 = new Label();
        mv.visitLabel(l2);
        mv.visitFrame(F_APPEND, 3, new Object[]{"[" + itemName,
                INTEGER, INTEGER}, 0, null);
        mv.visitVarInsn(ILOAD, 5);
        mv.visitVarInsn(ILOAD, 4);
        l3 = new Label();
        mv.visitJumpInsn(IF_ICMPGE, l3);
        String writeMethod = getWriteMethod(pfi.protoField.type());
        if (pfi.protoField.type() != Type.OBJECT) {
            mv.visitVarInsn(ALOAD, 0);
            mv.visitVarInsn(ALOAD, 1);
            mv.visitVarInsn(ALOAD, 2);
            mv.visitVarInsn(ALOAD, 3);
            mv.visitVarInsn(ILOAD, 5);
            mv.visitInsn(AALOAD);
            visitMethod(mv, INVOKEVIRTUAL, pojoCodecName, writeMethod,
                    "(L" + WRITER_NAME + ";[B" + itemName + ")V", false);
        } else {
            mv.visitVarInsn(ALOAD, 1);
            mv.visitVarInsn(ALOAD, 2);
            mv.visitVarInsn(ALOAD, 3);
            mv.visitVarInsn(ILOAD, 5);
            mv.visitInsn(AALOAD);
            visitMethod(mv, INVOKEINTERFACE, WRITER_NAME, writeMethod,
                    "([BLjava/lang/Object;)V", true);
        }
        mv.visitIincInsn(5, 1);

        mv.visitJumpInsn(GOTO, l2);
        mv.visitLabel(l3);
        mv.visitFrame(F_CHOP,3, null, 0, null);
        mv.visitInsn(RETURN);
        mv.visitMaxs(4, 8);
        mv.visitEnd();


        arrayMethods.add(typed);
    }

    /**
     * 根据tag的值判断该tag应该执行什么Opcode的操作
     * @param mv 方法
     * @param tag protobug对象tag的值
     */
    private void visitTagOpcode(MethodVisitor mv, int tag) {
        switch (tag) {
            case 0:
                mv.visitInsn(ICONST_0);
                break;
            case 1:
                mv.visitInsn(ICONST_1);
                break;
            case 2:
                mv.visitInsn(ICONST_2);
                break;
            case 3:
                mv.visitInsn(ICONST_3);
                break;
            case 4:
                mv.visitInsn(ICONST_4);
                break;
            case 5:
                mv.visitInsn(ICONST_5);
                break;
            default:
                if (tag <= Short.MAX_VALUE) {
                    mv.visitIntInsn(SIPUSH, tag);
                } else {
                    mv.visitLdcInsn(new Integer(tag));
                }
                break;
        }
    }

    private void visitEncodeBridgeMethod() {
        MethodVisitor mv;
        mv = cw.visitMethod(ACC_PUBLIC + ACC_BRIDGE + ACC_SYNTHETIC, "encode",
                "(L" + WRITER_NAME + ";Ljava/lang/Object;)V", null, null);

        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitVarInsn(ALOAD, 2);
        mv.visitTypeInsn(CHECKCAST, toInternalName(pojoCls.getName()));
        visitMethod(mv, INVOKEVIRTUAL, pojoCodecName, "encode",
                "(L" + WRITER_NAME + ";L" + toInternalName(pojoCls.getName())
                        + ";)V", false);
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    private String getPojoEncoderName(java.lang.reflect.Type type) {
        return "encoder" + ((Class)type).getSimpleName();
    }

    private String getMapCodecName(Class mapCls) {
        return "encoderMap" + mapCls.getSimpleName();
    }

    private void visitGetEncoderMethods(List<java.lang.reflect.Type> pojoTypes) {
        if (CollectionUtils.isEmpty(pojoTypes)) {
            return;
        }
        for (java.lang.reflect.Type type : pojoTypes) {
            if (type  instanceof Class
                    && !((Class)type).getName().equals(pojoCls.getName())) {
                visitGetEncoderMethod(type);
            }
        }
    }

    private void visitGetEncoderMethod(java.lang.reflect.Type type) {
        String itemType = toInternalName(((Class)type).getName());
        String codecName = getPojoEncoderName(type);
        if (codecMethods.contains(codecName)) {
            return;
        }
        String methodName = codecName.substring(0, 1).toUpperCase(Locale.ENGLISH) + codecName.substring(1);
        MethodVisitor mv = cw.visitMethod(ACC_PRIVATE, "get" + methodName, "()L" + IFACE_NAME + ";",
                "()L" + IFACE_NAME + "<L" + itemType + ";>;", null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitFieldInsn(GETFIELD, pojoCodecName, codecName, "L" + IFACE_NAME + ";");
        Label l0 = new Label();
        mv.visitJumpInsn(IFNONNULL, l0);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitFieldInsn(GETSTATIC, REGISTER_NAME, "INSTANCE", "L" + REGISTER_NAME + ";");
        mv.visitLdcInsn(org.objectweb.asm.Type.getType("L" + itemType + ";"));
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKESPECIAL, pojoCodecName, "getProtoBufOption",
                "()L" + PROTOBUF_OPTIION_NAME + ";", false);
        mv.visitMethodInsn(INVOKEVIRTUAL, REGISTER_NAME, "getEncoder",
                "(Ljava/lang/Class;L" + PROTOBUF_OPTIION_NAME + ";)L" + IFACE_NAME + ";", false);
        mv.visitFieldInsn(PUTFIELD, pojoCodecName, codecName, "L" + IFACE_NAME + ";");
        mv.visitLabel(l0);
        mv.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitFieldInsn(GETFIELD, pojoCodecName, codecName, "L" + IFACE_NAME + ";");
        mv.visitInsn(ARETURN);
        mv.visitMaxs(3, 1);
        mv.visitEnd();

        codecMethods.add(codecName);
    }

    private void visitInitMethod(List<java.lang.reflect.Type> pojoTypes, List<ProtoFieldInfo> fields) {

        for (java.lang.reflect.Type type : pojoTypes) {
            String itemType = toInternalName(((Class)type).getName());
            String codecName = getPojoEncoderName(type);
            String encoderName = toInternalName(getEncoderName((Class)type, option));
            if (!encoderName.equals(pojoCodecName) && !codecNames.contains(codecName)) {
                cw.visitField(ACC_PRIVATE, codecName, "L" + IFACE_NAME + ";",
                        "L" + IFACE_NAME + "<L" + itemType + ";>;", null);
                codecNames.add(codecName);
            }
        }

        MethodVisitor mv;
        mv = cw.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        visitMethod(mv, INVOKESPECIAL, parentName, "<init>", "()V", false);

        int varMapEntry = 1;
        List<String> mapEntryFields = new ArrayList<>();
        for (ProtoFieldInfo pfi : mapFields) {
            String mapDecoderName = buildMapDecoderName(pfi.field.getGenericType(), option);
            String simpleName     = getSimpleName(mapDecoderName);
            String fieldName      = lowerCaseFirstChar(simpleName);
            if (!mapEntryFields.contains(fieldName)) {
                MapEntryTypeInfo info = getMapEntryTypeInfo(pfi.field.getGenericType());
                FieldVisitor fv = cw.visitField(ACC_PRIVATE, fieldName, "L" + MAP_ENTRY_ENCODER_NAME + ";",
                        "L" + MAP_ENTRY_ENCODER_NAME + "<" + getDescriptor(info.getKeyType()) +
                                getDescriptor(info.getValueType()) + ">;", null);

                fv.visitEnd();

                mv.visitLdcInsn(org.objectweb.asm.Type.getType("L" + pojoName + ";"));
                mv.visitLdcInsn(pfi.field.getName());
                mv.visitMethodInsn(INVOKESTATIC, "io/edap/util/AsmUtil", "getFieldType",
                        "(Ljava/lang/Class;Ljava/lang/String;)Ljava/lang/reflect/Type;", false);
                mv.visitVarInsn(ASTORE, varMapEntry);
                mv.visitVarInsn(ALOAD, varMapEntry);
                mv.visitMethodInsn(INVOKESTATIC, "io/edap/util/AsmUtil", "getMapType",
                        "(Ljava/lang/reflect/Type;)Ljava/lang/reflect/Type;", false);
                mv.visitVarInsn(ASTORE, varMapEntry);
                mv.visitVarInsn(ALOAD, 0);
                mv.visitFieldInsn(GETSTATIC, REGISTER_NAME, "INSTANCE", "L" + REGISTER_NAME + ";");
                mv.visitVarInsn(ALOAD, varMapEntry);
                mv.visitInsn(ACONST_NULL);
                mv.visitTypeInsn(CHECKCAST, "java/lang/Class");
                mv.visitFieldInsn(GETSTATIC, pojoCodecName, "PROTO_BUF_OPTION", "L" + PROTOBUF_OPTIION_NAME +";");
                mv.visitMethodInsn(INVOKEVIRTUAL, REGISTER_NAME, "getMapEntryEncoder",
                        "(Ljava/lang/reflect/Type;Ljava/lang/Class;L" + PROTOBUF_OPTIION_NAME + ";)L" +
                                MAP_ENTRY_ENCODER_NAME + ";", false);
                mv.visitFieldInsn(PUTFIELD, pojoCodecName, fieldName, "L" + MAP_ENTRY_ENCODER_NAME + ";");

                mapEntryFields.add(fieldName);
                varMapEntry++;
            }
        }

        for (ProtoFieldInfo pfi : mapTypes) {
            java.lang.reflect.Type mapType = getMapType(pfi.field.getGenericType());
            String mapDecoderName = buildMapDecoderName(mapType, option);
            String simpleName     = getSimpleName(mapDecoderName);
            String fieldName      = lowerCaseFirstChar(simpleName);
            if (!mapEntryFields.contains(fieldName)) {
                MapEntryTypeInfo info = getMapEntryTypeInfo(mapType);
                FieldVisitor fv = cw.visitField(ACC_PRIVATE, fieldName, "L" + MAP_ENTRY_ENCODER_NAME + ";",
                        "L" + MAP_ENTRY_ENCODER_NAME + "<" + getDescriptor(info.getKeyType()) +
                                getDescriptor(info.getValueType()) + ">;", null);

                fv.visitEnd();

                mv.visitLdcInsn(org.objectweb.asm.Type.getType("L" + pojoName + ";"));
                mv.visitLdcInsn(pfi.field.getName());
                mv.visitMethodInsn(INVOKESTATIC, "io/edap/util/AsmUtil", "getFieldType",
                        "(Ljava/lang/Class;Ljava/lang/String;)Ljava/lang/reflect/Type;", false);
                mv.visitVarInsn(ASTORE, varMapEntry);
                mv.visitVarInsn(ALOAD, varMapEntry);
                mv.visitMethodInsn(INVOKESTATIC, "io/edap/util/AsmUtil", "getMapType",
                        "(Ljava/lang/reflect/Type;)Ljava/lang/reflect/Type;", false);
                mv.visitVarInsn(ASTORE, varMapEntry);
                mv.visitVarInsn(ALOAD, 0);
                mv.visitFieldInsn(GETSTATIC, REGISTER_NAME, "INSTANCE", "L" + REGISTER_NAME + ";");
                mv.visitVarInsn(ALOAD, varMapEntry);
                mv.visitInsn(ACONST_NULL);
                mv.visitTypeInsn(CHECKCAST, "java/lang/Class");
                mv.visitFieldInsn(GETSTATIC, pojoCodecName, "PROTO_BUF_OPTION", "L" + PROTOBUF_OPTIION_NAME +";");
                mv.visitMethodInsn(INVOKEVIRTUAL, REGISTER_NAME, "getMapEntryEncoder",
                        "(Ljava/lang/reflect/Type;Ljava/lang/Class;L" + PROTOBUF_OPTIION_NAME + ";)L" +
                                MAP_ENTRY_ENCODER_NAME + ";", false);
                mv.visitFieldInsn(PUTFIELD, pojoCodecName, fieldName, "L" + MAP_ENTRY_ENCODER_NAME + ";");

                mapEntryFields.add(fieldName);
            }
        }

        visitReflectField(mv, fields);

        mv.visitInsn(RETURN);
        mv.visitMaxs(5, 3);
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

    private void visitClinitMethod(List<ProtoFieldInfo> fields) {
        MethodVisitor mv;
        mv = cw.visitMethod(ACC_STATIC, "<clinit>", "()V", null, null);
        mv.visitCode();

        initFieldData(mv, fields);

        mv.visitInsn(RETURN);
        mv.visitMaxs(2, 0);
        mv.visitEnd();
    }

    /**
     * 初始化每个Field对应的tag+WireType的本地静态变量
     */
    private void initFieldData(MethodVisitor mv, List<ProtoFieldInfo> fields) {
        FieldVisitor fv;
        boolean isFast = false;
        if (option != null && CodecType.FAST == option.getCodecType()) {
            isFast = true;
        }

        FieldVisitor fvOption = cw.visitField(ACC_PRIVATE | ACC_FINAL | ACC_STATIC, "PROTO_BUF_OPTION",
                "L" + PROTOBUF_OPTIION_NAME + ";", null, null);
        fvOption.visitEnd();

        mv.visitTypeInsn(NEW, PROTOBUF_OPTIION_NAME);
        mv.visitInsn(DUP);
        mv.visitMethodInsn(INVOKESPECIAL, PROTOBUF_OPTIION_NAME, "<init>", "()V", false);
        mv.visitFieldInsn(PUTSTATIC, pojoCodecName, "PROTO_BUF_OPTION", "L" + PROTOBUF_OPTIION_NAME + ";");
        if (isFast) {
            mv.visitFieldInsn(GETSTATIC, pojoCodecName, "PROTO_BUF_OPTION", "L" + PROTOBUF_OPTIION_NAME + ";");
            mv.visitFieldInsn(GETSTATIC, "io/edap/protobuf/CodecType",
                    "FAST", "Lio/edap/protobuf/CodecType;");
            mv.visitMethodInsn(INVOKEVIRTUAL, PROTOBUF_OPTIION_NAME, "setCodecType",
                    "(Lio/edap/protobuf/CodecType;)V", false);
        }

        for (ProtoFieldInfo pfi : fields) {
            fv = cw.visitField(ACC_PRIVATE + ACC_FINAL + ACC_STATIC,
                    "tag" + pfi.protoField.tag(), "[B", null, null);
            fv.visitEnd();

            visitTagOpcode(mv, pfi.protoField.tag());
            if (isFast) {
                if (pfi.protoField.type() == Type.MESSAGE || pfi.protoField.type() == Type.MAP) {
                    mv.visitFieldInsn(GETSTATIC, FIELD_TYPE_NAME, Type.GROUP.name(),
                            "L" + FIELD_TYPE_NAME + ";");
                } else {
                    mv.visitFieldInsn(GETSTATIC, FIELD_TYPE_NAME, pfi.protoField.type().name(),
                            "L" + FIELD_TYPE_NAME + ";");
                }
            } else {
                mv.visitFieldInsn(GETSTATIC, FIELD_TYPE_NAME, pfi.protoField.type().name(), "L" + FIELD_TYPE_NAME + ";");
            }
            //mv.visitFieldInsn(GETSTATIC, FIELD_TYPE_NAME, pfi.protoField.type().name(), "L" + FIELD_TYPE_NAME + ";");
            mv.visitFieldInsn(GETSTATIC, CARDINALITY_NAME, pfi.protoField.cardinality().name(), "L" + CARDINALITY_NAME + ";");
            if (isFast) {
                mv.visitFieldInsn(GETSTATIC, "io/edap/protobuf/wire/Syntax", "PROTO_3",
                        "Lio/edap/protobuf/wire/Syntax;");
                mv.visitFieldInsn(GETSTATIC, pojoCodecName, "PROTO_BUF_OPTION", "L" + PROTOBUF_OPTIION_NAME +";");
                visitMethod(mv, INVOKESTATIC, PROTOUTIL_NAME, "buildFieldData",
                        "(IL" + FIELD_TYPE_NAME + ";L" + CARDINALITY_NAME
                                + ";Lio/edap/protobuf/wire/Syntax;" +
                                "Lio/edap/protobuf/model/ProtoBufOption;)[B", false);
            } else {
                visitMethod(mv, INVOKESTATIC, PROTOUTIL_NAME, "buildFieldData",
                        "(IL" + FIELD_TYPE_NAME + ";L" + CARDINALITY_NAME + ";)[B", false);
            }
            mv.visitFieldInsn(PUTSTATIC, pojoCodecName, "tag" + pfi.protoField.tag(), "[B");
        }
        // 如果父类是Map类型初始化tag为
        if (parentMapType != null) {
            fv = cw.visitField(ACC_PRIVATE + ACC_FINAL + ACC_STATIC, "tag" + WireFormat.RESERVED_TAG_VALUE_START, "[B", null, null);
            fv.visitEnd();
            visitTagOpcode(mv, WireFormat.RESERVED_TAG_VALUE_START);
            mv.visitFieldInsn(GETSTATIC, FIELD_TYPE_NAME, "MAP", "L" + FIELD_TYPE_NAME + ";");
            mv.visitFieldInsn(GETSTATIC, CARDINALITY_NAME, "OPTIONAL", "L" + CARDINALITY_NAME + ";");
            visitMethod(mv, INVOKESTATIC, PROTOUTIL_NAME, "buildFieldData",
                    "(IL" + FIELD_TYPE_NAME + ";L" + CARDINALITY_NAME + ";)[B", false);
            mv.visitFieldInsn(PUTSTATIC, pojoCodecName, "tag" + WireFormat.RESERVED_TAG_VALUE_START, "[B");
        }
    }

    private static String getEncoderDescriptor(Class msgCls) {
        StringBuilder sb = new StringBuilder();
        sb.append(getDescriptor(AbstractEncoder.class));
        sb.append("L").append(IFACE_NAME).append("<");
        sb.append(getDescriptor(msgCls));
        sb.append(">;");
        return sb.toString();
    }

    static String getEncoderName(Class pojoCls, ProtoBufOption option) {
        if (pojoCls == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder("pbe");
        if (option != null && option.getCodecType() != null) {
            switch (option.getCodecType()) {
                case FAST:
                    sb.append('f');
                    break;
                default:
                    break;
            }
        }
        sb.append('.');
        sb.append(pojoCls.getName());
        sb.append("Encoder");
        return sb.toString();
    }
}
