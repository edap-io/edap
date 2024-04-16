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

package io.edap.beanconvert;

import io.edap.beanconvert.AbstractConvertor.ConvertFieldInfo;
import io.edap.beanconvert.util.ConvertUtil;
import io.edap.util.CollectionUtils;
import io.edap.util.internal.GeneratorClassInfo;
import org.objectweb.asm.*;

import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.util.*;

import static io.edap.beanconvert.ConvertorRegister.getConvertorName;
import static io.edap.beanconvert.util.ConvertUtil.CONVERT_LIST_METHOD;
import static io.edap.beanconvert.util.ConvertUtil.getConvertFields;
import static io.edap.util.AsmUtil.*;
import static io.edap.util.ClazzUtil.getDescriptor;
import static org.objectweb.asm.Opcodes.*;

/**
 * Bean转换器class生成器,根据指定的原始Class以及返回的class生成转换器的实现
 */
public class ConvertorGenerator {

    static String PARENT_ANME = AbstractConvertor.class.getName();
    static String REGISTER_NAME = toInternalName(ConvertorRegister.class.getName());
    static String MAPPER_NAME = toInternalName(MapperRegister.class.getName());
    static String IFACE_NAME = toInternalName(Convertor.class.getName());
    static String CONVERTOR_REGISTER_NAME = toInternalName(ConvertorRegister.class.getName());

    private Class orignalCls;
    private Class destCls;
    private String convertorName;
    private List<MapperConfig> configs;

    private ClassWriter cw;

    public ConvertorGenerator(Class orignalCls, Class destCls, List<MapperConfig> configs) {
        this.orignalCls = orignalCls;
        this.destCls = destCls;
        this.configs = configs;
        this.convertorName = getConvertorName(orignalCls, destCls);
    }

    public GeneratorClassInfo getClassInfo() {
        GeneratorClassInfo gci = new GeneratorClassInfo();
        gci.clazzName = toInternalName(convertorName);

        String parentName = toInternalName(PARENT_ANME);
        String convertorDescriptor = "L" + parentName + "<L" + toInternalName(orignalCls.getName())
                + ";L" + toInternalName(destCls.getName()) + ";>;";

        String[] ifaceName = null;


        //定义编码器名称，继承的虚拟编码器以及实现的接口
        cw = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        cw.visit(V1_8, ACC_PUBLIC + ACC_SUPER, toInternalName(convertorName),
                convertorDescriptor, parentName, ifaceName);
        //cw.visit(52, ACC_PUBLIC + ACC_SUPER, "ebc/io/edap/x/beanconvert/test/CarToCarDtoConvertor", "Lio/edap/x/beanconvert/AbstractConvertor<Lio/edap/x/beanconvert/test/model/Car;Lio/edap/x/beanconvert/test/dto/CarDto;>;", "io/edap/x/beanconvert/AbstractConvertor", null);

        Map<String, ConvertFieldInfo> orignalFields = getConvertFields(orignalCls);
        Map<String, ConvertFieldInfo> destFields = getConvertFields(destCls);
        Map<String, MapperConfig> mapperConfigs = new HashMap<>();
        MapperInfo mapperInfo = MapperRegister.instance().getMapperInfo(orignalCls, destCls);
        List<MapperConfig> mcs = MapperRegister.instance().getParentMapperConfigs(orignalCls, destCls);
        if (!CollectionUtils.isEmpty(mcs)) {
            for (MapperConfig mc : mcs) {
                mapperConfigs.put(mc.getOriginalName(), mc);
            }
        }
        if (mapperInfo != null && !CollectionUtils.isEmpty(mapperInfo.getConfigList())) {
            for (MapperConfig config : mapperInfo.getConfigList()) {
                mapperConfigs.put(config.getOriginalName(), config);
            }
        }

        List<ConvertInfo> convertInfos = new ArrayList<>();
        for (Map.Entry<String, ConvertFieldInfo> entry : orignalFields.entrySet()) {
            MapperConfig fieldConfig = mapperConfigs.get(entry.getKey());
            // 如果原bean的field的属性存在配置则使用进行设置
            if (fieldConfig != null) {
                ConvertFieldInfo destField = destFields.get(fieldConfig.getDestName());
                ConvertInfo cinfo = new ConvertInfo();
                cinfo.destInfo = destField;
                cinfo.orignalInfo = entry.getValue();
                cinfo.config = fieldConfig;
                convertInfos.add(cinfo);
            } else {
                ConvertFieldInfo destField = destFields.get(entry.getKey());
                if (destField == null) {
                    continue;
                }
                ConvertInfo cinfo = new ConvertInfo();
                cinfo.destInfo = destField;
                cinfo.orignalInfo = entry.getValue();
                convertInfos.add(cinfo);
            }
        }

        // 生成初始化函数
        visitInitMethod();
        // 生成convert函数
        visitConvertMethod(convertInfos);

        visitClinitMethod(convertInfos);

        visitEncodeBridgeMethod();

        cw.visitEnd();

        gci.clazzBytes = cw.toByteArray();
        return gci;
    }

    static class ConvertInfo {
        public ConvertFieldInfo orignalInfo;
        public ConvertFieldInfo destInfo;
        public MapperConfig config;
    }

    private void visitConvertMethod(List<ConvertInfo> convertInfos) {
        Collections.sort(convertInfos, (o1, o2) -> {
            if (o1.destInfo.seq > o2.destInfo.seq) {
                return 1;
            } else if (o1.destInfo.seq < o2.destInfo.seq) {
                return -1;
            }
            return 0;
        });
        MethodVisitor mv;
        mv = cw.visitMethod(ACC_PUBLIC, "convert", "(L" + toInternalName(orignalCls.getName())
                + ";)L" + toInternalName(destCls.getName()) + ";", null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 1);
        mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Object", "getClass", "()Ljava/lang/Class;", false);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKEVIRTUAL, toInternalName(convertorName), "getOrignalClazz", "()Ljava/lang/Class;", false);
        Label l0 = new Label();
        mv.visitJumpInsn(IF_ACMPEQ, l0);
        mv.visitMethodInsn(INVOKESTATIC, REGISTER_NAME, "instance", "()L" + REGISTER_NAME + ";", false);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Object", "getClass", "()Ljava/lang/Class;", false);
        mv.visitLdcInsn(Type.getType("L" + toInternalName(destCls.getName()) + ";"));
        mv.visitMethodInsn(INVOKEVIRTUAL, REGISTER_NAME, "getConvertor", "(Ljava/lang/Class;Ljava/lang/Class;)L"
                + toInternalName(PARENT_ANME) + ";", false);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitMethodInsn(INVOKEVIRTUAL, toInternalName(PARENT_ANME), "convert", "(Ljava/lang/Object;)Ljava/lang/Object;", false);
        mv.visitTypeInsn(CHECKCAST, toInternalName(destCls.getName()));
        mv.visitInsn(ARETURN);
        mv.visitLabel(l0);
        mv.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
        mv.visitTypeInsn(NEW, toInternalName(destCls.getName()));
        mv.visitInsn(DUP);
        mv.visitMethodInsn(INVOKESPECIAL, toInternalName(destCls.getName()), "<init>", "()V", false);
        mv.visitVarInsn(ASTORE, 2);

        if (!CollectionUtils.isEmpty(convertInfos)) {
            for (ConvertInfo cinfo : convertInfos) {
                visitSetValue(mv, cinfo);
            }
        }
        mv.visitVarInsn(ALOAD, 2);
        mv.visitInsn(ARETURN);
        mv.visitMaxs(2, 3);
        mv.visitEnd();
    }

    private String getFieldConvertorName(String name) {
        return name.toUpperCase(Locale.ENGLISH) + "_CONVERTOR";
    }

    private boolean isSameType(Field orignal, Field dest) {
        if (orignal.getType().getName().equals(dest.getType().getName())) {
            return true;
        }
        if (isBoxedType(orignal.getType().getName(), dest.getType().getName())) {
            return true;
        }
        if (isBoxedType(dest.getType().getName(), orignal.getType().getName())) {
            return true;
        }
        return false;
    }

    private boolean isBoxedType(String name, String boxedName) {
        switch (name) {
            case "boolean":
                return Boolean.class.getName().equals(boxedName);
            case "byte":
                return Byte.class.getName().equals(boxedName);
            case "short":
                return Short.class.getName().equals(boxedName);
            case "char":
                return Character.class.getName().equals(boxedName);
            case "int":
                return Integer.class.getName().equals(boxedName);
            case "long":
                return Long.class.getName().equals(boxedName);
            case "float":
                return Float.class.getName().equals(boxedName);
            case "double":
                return Double.class.getName().equals(boxedName);
            default:
                return false;
        }
    }

    private UnboxInfo getUnboxInfo(String name) {
        UnboxInfo info = new UnboxInfo();
        switch (name) {
            case "boolean":
                info.boxedName = Boolean.class.getName();
                info.method = "booleanValue";
                info.baseName = getDescriptor(boolean.class);
                return info;
            case "byte":
                info.boxedName = Byte.class.getName();
                info.method = "byteValue";
                info.baseName = getDescriptor(byte.class);
                return info;
            case "short":
                info.boxedName = Short.class.getName();
                info.method = "shortValue";
                info.baseName = getDescriptor(short.class);
                return info;
            case "char":
                info.boxedName = Character.class.getName();
                info.method = "charValue";
                info.baseName = getDescriptor(char.class);
                return info;
            case "int":
                info.boxedName = Integer.class.getName();
                info.method = "intValue";
                info.baseName = getDescriptor(int.class);
                return info;
            case "long":
                info.boxedName = Long.class.getName();
                info.method = "longValue";
                info.baseName = getDescriptor(long.class);
                return info;
            case "float":
                info.boxedName = Float.class.getName();
                info.method = "floatValue";
                info.baseName = getDescriptor(float.class);
                return info;
            case "double":
                info.boxedName = Double.class.getName();
                info.method = "doubleValue";
                info.baseName = getDescriptor(double.class);
                return info;
            default:
                return null;
        }
    }

    static class UnboxInfo {
        public String boxedName;
        public String baseName;
        public String method;
    }

    private void visitSetValue(MethodVisitor mv, ConvertInfo cinfo) {

        if (cinfo == null) {
            return;
        }

        ConvertFieldInfo orignalInfo = cinfo.orignalInfo;
        ConvertFieldInfo destInfo = cinfo.destInfo;

        String rType = getDescriptor(orignalInfo.field.getType());
        int varInt = 3;
        if (cinfo.config != null) {
            if (cinfo.config.getConvertor() != null) {
                String fieldConvertorName = getFieldConvertorName(orignalInfo.field.getName());
                mv.visitVarInsn(ALOAD, 2);
                mv.visitFieldInsn(GETSTATIC, toInternalName(convertorName), fieldConvertorName, "L" + IFACE_NAME + ";");
                visitGetFieldValue(mv, orignalCls, orignalInfo, rType);
                String origType = toInternalName(orignalInfo.field.getType().getName());
                switch (origType) {
                    case "byte":
                        mv.visitMethodInsn(INVOKESTATIC, "java/lang/Byte", "valueOf", "(B)Ljava/lang/Byte;", false);
                        break;
                    case "char":
                        mv.visitMethodInsn(INVOKESTATIC, "java/lang/Character", "valueOf", "(C)Ljava/lang/Character;", false);
                        break;
                    case "int":
                        mv.visitMethodInsn(INVOKESTATIC, "java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;", false);
                        break;
                    case "short":
                        mv.visitMethodInsn(INVOKESTATIC, "java/lang/Short", "valueOf", "(S)Ljava/lang/Short;", false);
                        break;
                    case "long":
                        mv.visitMethodInsn(INVOKESTATIC, "java/lang/Long", "valueOf", "(J)Ljava/lang/Long;", false);
                        break;
                    case "float":
                        mv.visitMethodInsn(INVOKESTATIC, "java/lang/Float", "valueOf", "(F)Ljava/lang/Float;", false);
                        break;
                    case "double":
                        mv.visitMethodInsn(INVOKESTATIC, "java/lang/Double", "valueOf", "(D)Ljava/lang/Double;", false);
                        break;
                    case "boolean":
                        mv.visitMethodInsn(INVOKESTATIC, "java/lang/Boolean", "valueOf", "(Z)Ljava/lang/Boolean;", false);
                        break;
                    default:
                        mv.visitTypeInsn(CHECKCAST, origType);
                        break;
                }

                mv.visitMethodInsn(INVOKEINTERFACE, IFACE_NAME, "convert", "(Ljava/lang/Object;)Ljava/lang/Object;", true);
                String destType = toInternalName(destInfo.field.getType().getName());
                switch (destType) {
                    case "int":
                        mv.visitTypeInsn(CHECKCAST, toInternalName(Integer.class.getName()));
                        break;
                    case "long":
                        mv.visitTypeInsn(CHECKCAST, toInternalName(Long.class.getName()));
                        break;
                    case "boolean":
                        mv.visitTypeInsn(CHECKCAST, toInternalName(Boolean.class.getName()));
                        break;
                    default:
                        mv.visitTypeInsn(CHECKCAST, toInternalName(destInfo.field.getType().getName()));
                        break;
                }

                visitSetValueOpcode(mv, destCls, orignalInfo, destInfo, cinfo.config.getConvertor());
                return;
            }
        }

        if (isSameType(orignalInfo.field, destInfo.field)) {
            if (isList(orignalInfo.field.getGenericType())) {
                ListConvertInfo needConvertInfo = isNeedConvertList(orignalInfo.field.getGenericType(), destInfo.field.getGenericType());
                if (needConvertInfo.isNeed) {
                    mv.visitVarInsn(ALOAD, 2);
                    visitGetFieldValue(mv, orignalCls, orignalInfo, rType);
                    String listConvert = toInternalName(ConvertorRegister.instance().createListConvert(needConvertInfo.orignalType, needConvertInfo.destType));
                    mv.visitMethodInsn(INVOKESTATIC, listConvert, CONVERT_LIST_METHOD, "(Ljava/util/List;)Ljava/util/List;", false);
                    visitSetValueOpcode(mv, destCls, orignalInfo, destInfo);
                    return;
                }
            }
            mv.visitVarInsn(ALOAD, 2);
            visitGetFieldValue(mv, orignalCls, orignalInfo, rType);
            //mv.visitMethodInsn(INVOKEVIRTUAL, origalName, "getMake", "()Ljava/lang/String;", false);

            visitSetValueOpcode(mv, destCls, orignalInfo, destInfo);
            //mv.visitMethodInsn(INVOKEVIRTUAL, destName, "setMake", "(Ljava/lang/String;)V", false);
        } else if ("java.lang.String".equals(destInfo.field.getType().getName())) {
            mv.visitVarInsn(ALOAD, 2);
            visitGetFieldValue(mv, orignalCls, orignalInfo, rType);
            switch (orignalInfo.field.getType().getName()) {
                case "boolean":
                case "char":
                case "int":
                case "long":
                case "float":
                case "double":
                    mv.visitMethodInsn(INVOKESTATIC, "java/lang/String", "valueOf", "(" + rType + ")Ljava/lang/String;", false);
                    break;
                case "byte":
                case "short":
                    mv.visitVarInsn(ISTORE, varInt);
                    mv.visitVarInsn(ILOAD, varInt);
                    varInt++;
                    mv.visitMethodInsn(INVOKESTATIC, "java/lang/String", "valueOf", "(I)Ljava/lang/String;", false);
                    break;
                default:
                    mv.visitMethodInsn(INVOKESTATIC, "java/lang/String", "valueOf", "(Ljava/lang/Object;)Ljava/lang/String;", false);
                    break;
            }

            visitSetValueOpcode(mv, destCls, orignalInfo, destInfo);
        } else {
            String fieldConvertorName = getFieldConvertorName(orignalInfo.field.getName());
            mv.visitVarInsn(ALOAD, 2);
            mv.visitFieldInsn(GETSTATIC, toInternalName(convertorName), fieldConvertorName, "L" + IFACE_NAME + ";");
            visitGetFieldValue(mv, orignalCls, orignalInfo, rType);
            //mv.visitMethodInsn(INVOKEVIRTUAL, "io/edap/x/beanconvert/test/model/Car", "getType", "()Lio/edap/x/beanconvert/test/model/CarType;", false);
            mv.visitMethodInsn(INVOKEINTERFACE, IFACE_NAME, "convert", "(Ljava/lang/Object;)Ljava/lang/Object;", true);
            mv.visitTypeInsn(CHECKCAST, toInternalName(destInfo.field.getType().getName()));
            visitSetValueOpcode(mv, destCls, orignalInfo, destInfo);
        }


    }

    static class ListConvertInfo {
        public boolean isNeed;
        public Class orignalType;
        public Class destType;
    }

    private ListConvertInfo isNeedConvertList(java.lang.reflect.Type orignalType, java.lang.reflect.Type destType) {
        // 如果原目标List未指定类型则可以直接转，如果目的类型指定类型并且目的类型是原类型或者原类型的父类则无需转换
        ListConvertInfo info = new ListConvertInfo();
        if (destType instanceof ParameterizedType) {
            ParameterizedType destPt = (ParameterizedType) destType;
            ParameterizedType orignalPt = (ParameterizedType) orignalType;
            java.lang.reflect.Type destRawType = destPt.getRawType();
            java.lang.reflect.Type orignalRawType = orignalPt.getRawType();
            java.lang.reflect.Type[] destActualTypes = destPt.getActualTypeArguments();

            java.lang.reflect.Type[] orignalActualTypes = orignalPt.getActualTypeArguments();
            info.isNeed = !isInherit((Class) orignalActualTypes[0], (Class) destActualTypes[0]);
            info.orignalType = (Class) orignalActualTypes[0];
            info.destType = (Class) destActualTypes[0];
            return info;
        } else {
            return info;
        }
    }

    private boolean isInherit(Class orignalCls, Class destCls) {
        if (orignalCls.getName().equals(destCls.getName())) {
            return true;
        }
        Class pClass = orignalCls.getSuperclass();
        while (pClass != null && pClass != Object.class) {
            if (pClass == destCls) {
                return true;
            }
            pClass = pClass.getSuperclass();
        }
        return false;
    }

    private void visitSetValueOpcode(MethodVisitor mv, Class beanClass, ConvertFieldInfo orignalInfo,
                                     ConvertFieldInfo pfi) {
        visitSetValueOpcode(mv, beanClass, orignalInfo, pfi, null);
    }

    private void visitSetValueOpcode(MethodVisitor mv, Class beanClass, ConvertFieldInfo orignalInfo,
                                     ConvertFieldInfo pfi, Convertor convertor) {
        String valType = getDescriptor(pfi.field.getType());
        if (pfi.hasSetAccessed) {
            String otype = orignalInfo.field.getType().getName();
            String dtype = pfi.field.getType().getName();
            if (convertor != null && convertor instanceof AbstractConvertor) {
                AbstractConvertor conv = (AbstractConvertor) convertor;
                otype = conv.getDestClazz().getName();
            }
            if (pfi.setMethod != null) {
                String rtnDesc = getDescriptor(pfi.setMethod.getGenericReturnType());
                if (isBoxedType(otype, dtype)) {
                    String boxedName = toInternalName(pfi.field.getType().getName());
                    String unboxedName = getDescriptor(orignalInfo.field.getGenericType());
                    mv.visitMethodInsn(INVOKESTATIC, boxedName, "valueOf", "(" + unboxedName + ")L" + boxedName + ";", false);
                } else if (isBoxedType(dtype, otype)) {
                    UnboxInfo unboxInfo = getUnboxInfo(pfi.field.getType().getName());
                    if (unboxInfo != null) {
                        mv.visitMethodInsn(INVOKESTATIC, toInternalName(ConvertUtil.class.getName()), unboxInfo.method,
                                "(L" + toInternalName(unboxInfo.boxedName) + ";)" + unboxInfo.baseName, false);
                    }
                }
                visitMethod(mv, INVOKEVIRTUAL, toInternalName(beanClass.getName()), pfi.setMethod.getName(),
                        "(" + valType + ")" + rtnDesc, false);
            } else {
                if (isBoxedType(otype, dtype)) {
                    String boxedName = toInternalName(pfi.field.getType().getName());
                    String unboxedName = getDescriptor(orignalInfo.field.getGenericType());
                    mv.visitMethodInsn(INVOKESTATIC, boxedName, "valueOf", "(" + unboxedName + ")L" + boxedName + ";", false);
                } else if (isBoxedType(dtype, otype)) {
                    UnboxInfo unboxInfo = getUnboxInfo(pfi.field.getType().getName());
                    if (unboxInfo != null) {
                        mv.visitMethodInsn(INVOKESTATIC, toInternalName(ConvertUtil.class.getName()), unboxInfo.method,
                                "(L" + toInternalName(unboxInfo.boxedName) + ";)" + unboxInfo.baseName, false);
                    }
                }
                mv.visitFieldInsn(PUTFIELD, toInternalName(beanClass.getName()), pfi.field.getName(),
                        valType);
            }
        } else {
            switch (pfi.field.getType().getName()) {
                case "boolean":
                    visitMethod(mv, INVOKESTATIC, "java/lang/Boolean",
                            "valueOf", "(Z)Ljava/lang/Boolean;", false);
                    break;
                case "byte":
                    visitMethod(mv, INVOKESTATIC, "java/lang/Byte",
                            "valueOf", "(B)Ljava/lang/Byte;", false);
                    break;
                case "short":
                    visitMethod(mv, INVOKESTATIC, "java/lang/Short",
                            "valueOf", "(S)Ljava/lang/Short;", false);
                    break;
                case "char":
                    visitMethod(mv, INVOKESTATIC, "java/lang/Character",
                            "valueOf", "(C)Ljava/lang/Character;", false);
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
                case "double":
                    visitMethod(mv, INVOKESTATIC, "java/lang/Double",
                            "valueOf", "(D)Ljava/lang/Double;", false);
                    break;
            }
            mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/reflect/Field", "set",
                    "(Ljava/lang/Object;Ljava/lang/Object;)V", false);
        }
    }

    private String visitGetFieldValue(MethodVisitor mv, Class beanClass, ConvertFieldInfo pfi, String rType) {
        String pojoName = toInternalName(beanClass.getName());
        String type = rType;
        if (pfi.hasGetAccessed) {
            mv.visitVarInsn(ALOAD, 1);
            if (pfi.getMethod != null) {
                visitMethod(mv, INVOKEVIRTUAL, pojoName, pfi.getMethod.getName(),
                        "()" + rType, false);
//                String timeUtil = toInternalName(TimeUtil.class.getName());
//                if ("Ljava/util/Date;".equals(rType)) {
//                    visitMethod(mv, INVOKESTATIC, timeUtil, "timeMillis", "(Ljava/util/Date;)J", false);
//                    type = "J";
//                } else if ("Ljava/util/Calendar;".equals(rType)) {
//                    visitMethod(mv, INVOKESTATIC, timeUtil, "timeMillis", "(Ljava/util/Calendar;)J", false);
//                    type = "J";
//                } else if ("Ljava/time/LocalDateTime;".equals(rType)) {
//                    visitMethod(mv, INVOKESTATIC, timeUtil, "timeMillis", "(Ljava/time/LocalDateTime;)J", false);
//                    type = "J";
//                }
            } else {
                mv.visitFieldInsn(GETFIELD, pojoName, pfi.field.getName(), rType);
            }
        } else {
            mv.visitVarInsn(ALOAD, 0);
            mv.visitFieldInsn(GETFIELD, convertorName, pfi.field.getName() + "F",
                    "Ljava/lang/reflect/Field;");
            mv.visitVarInsn(ALOAD, 1);
            mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/reflect/Field",
                    "get", "(Ljava/lang/Object;)Ljava/lang/Object;", false);

            switch (rType) {
                case "B":
                    mv.visitTypeInsn(CHECKCAST, "java/lang/Byte");
                    type = "Ljava/lang/Byte;";
                    break;
                case "C":
                    mv.visitTypeInsn(CHECKCAST, "java/lang/Character");
                    type = "Ljava/lang/Character;";
                    break;
                case "I":
                    mv.visitTypeInsn(CHECKCAST, "java/lang/Integer");
                    type = "Ljava/lang/Integer;";
                    break;
                case "S":
                    mv.visitTypeInsn(CHECKCAST, "java/lang/Short");
                    type = "Ljava/lang/Integer;";
                    break;
                case "Z":
                    mv.visitTypeInsn(CHECKCAST, "java/lang/Boolean");
                    type = "Ljava/lang/Boolean;";
                    break;
                case "D":
                    mv.visitTypeInsn(CHECKCAST, "java/lang/Double");
                    type = "Ljava/lang/Double;";
                    break;
                case "F":
                    mv.visitTypeInsn(CHECKCAST, "java/lang/Float");
                    type = "Ljava/lang/Float;";
                    break;
                case "J":
                    mv.visitTypeInsn(CHECKCAST, "java/lang/Long");
                    type = "Ljava/lang/Long;";
                    break;
                default:
                    mv.visitTypeInsn(CHECKCAST, toInternalName(pfi.field.getType().getName()));
            }

        }
        return type;
    }

    private void visitInitMethod() {
        MethodVisitor mv;
        mv = cw.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKESPECIAL, toInternalName(PARENT_ANME), "<init>", "()V", false);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitLdcInsn(Type.getType("L" + toInternalName(orignalCls.getName()) + ";"));
        mv.visitMethodInsn(INVOKEVIRTUAL, toInternalName(convertorName), "setOrignalClazz", "(Ljava/lang/Class;)V", false);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitLdcInsn(Type.getType("L" + toInternalName(destCls.getName()) + ";"));
        mv.visitMethodInsn(INVOKEVIRTUAL, toInternalName(convertorName), "setDestClazz", "(Ljava/lang/Class;)V", false);

        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();

    }

    private void visitEncodeBridgeMethod() {
        MethodVisitor mv;
        mv = cw.visitMethod(ACC_PUBLIC + ACC_BRIDGE + ACC_SYNTHETIC, "convert",
                "(Ljava/lang/Object;)Ljava/lang/Object;", null, null);

        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitTypeInsn(CHECKCAST, toInternalName(orignalCls.getName()));
        visitMethod(mv, INVOKEVIRTUAL, toInternalName(convertorName), "convert",
                "(L" + toInternalName(orignalCls.getName())
                        + ";)L" + toInternalName(destCls.getName()) + ";", false);
        mv.visitInsn(ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    private void visitClinitMethod(List<ConvertInfo> convertInfos) {
        if (CollectionUtils.isEmpty(convertInfos)) {
            return;
        }
        List<ConvertInfo> needCinits = new ArrayList<>();
        MapperInfo mi = MapperRegister.instance().getMapperInfo(orignalCls, destCls);
        Map<String, MapperConfig> mcs = new HashMap<>();
        if (mi != null) {
            for (MapperConfig mc : mi.getConfigList()) {
                mcs.put(mc.getOriginalName(), mc);
            }
        }
        for (ConvertInfo cinfo : convertInfos) {
            Convertor convertor;
            if (mcs.containsKey(cinfo.orignalInfo.field.getName())) {
                convertor = mcs.get(cinfo.orignalInfo.field.getName()).getConvertor();
            } else {
                convertor = null;
            }
            if (isSameType(cinfo.orignalInfo.field, cinfo.destInfo.field) && convertor == null) {
                continue;
            } else if ("java.lang.String".equals(cinfo.destInfo.field.getType().getName())
                    && (!mcs.containsKey(cinfo.orignalInfo.field.getName())
                    || (mcs.containsKey(cinfo.orignalInfo.field.getName()) && convertor == null))) {
                if (cinfo.orignalInfo.field.getType().isPrimitive() || isPojo(cinfo.orignalInfo.field.getGenericType())
                        || cinfo.orignalInfo.field.getType().isEnum()) {
                    continue;
                }

            }
            needCinits.add(cinfo);
        }

        if (CollectionUtils.isEmpty(needCinits)) {
            return;
        }
        FieldVisitor fv;
        MethodVisitor mv;
        mv = cw.visitMethod(ACC_STATIC, "<clinit>", "()V", null, null);
        mv.visitCode();

        for (ConvertInfo cinfo : needCinits) {
            MapperConfig mc = mcs.get(cinfo.orignalInfo.field.getName());
            String fieldConvertorName = getFieldConvertorName(cinfo.orignalInfo.field.getName());
            String orignalType = toInternalName(cinfo.orignalInfo.field.getType().getName());
            if (cinfo.orignalInfo.field.getType().isPrimitive()) {
                switch (orignalType) {
                    case "int":
                        orignalType = "java/lang/Integer";
                        break;
                    case "boolean":
                        orignalType = "java/lang/Boolean";
                        break;
                    case "byte":
                        orignalType = "java/lang/Byte";
                        break;
                    case "char":
                        orignalType = "java/lang/Character";
                        break;
                    case "float":
                        orignalType = "java/lang/Float";
                        break;
                    case "double":
                        orignalType = "java/lang/Double";
                        break;
                    case "short":
                        orignalType = "java/lang/Short";
                        break;
                    case "long":
                        orignalType = "java/lang/Long";
                        break;
                }
            }
            //if (cinfo.orignalInfo.field.getType().isEnum()) {
            //    orignalType = toInternalName(Enum.class.getName());
            //}
            String destType = toInternalName(cinfo.destInfo.field.getType().getName());
            if (cinfo.destInfo.field.getType().isPrimitive()) {
                switch (destType) {
                    case "int":
                        destType = "java/lang/Integer";
                        break;
                    case "boolean":
                        destType = "java/lang/Boolean";
                        break;
                    case "byte":
                        destType = "java/lang/Byte";
                        break;
                    case "char":
                        destType = "java/lang/Character";
                        break;
                    case "float":
                        destType = "java/lang/Float";
                        break;
                    case "double":
                        destType = "java/lang/Double";
                        break;
                    case "short":
                        destType = "java/lang/Short";
                        break;
                    case "long":
                        destType = "java/lang/Long";
                        break;
                }
            }
            String ifaceDesc = "L" + IFACE_NAME + "<L" + orignalType + ";L"
                    + destType + ";>;";
            fv = cw.visitField(ACC_PRIVATE + ACC_FINAL + ACC_STATIC, fieldConvertorName, "L" + IFACE_NAME + ";", ifaceDesc, null);
            fv.visitEnd();
            if (mc == null || mc.getConvertor() == null) {
                mv.visitMethodInsn(INVOKESTATIC, CONVERTOR_REGISTER_NAME, "instance", "()L" + CONVERTOR_REGISTER_NAME + ";", false);
                mv.visitLdcInsn(Type.getType("L" + orignalType + ";"));
                mv.visitLdcInsn(Type.getType("L" + toInternalName(cinfo.destInfo.field.getType().getName()) + ";"));
                mv.visitMethodInsn(INVOKEVIRTUAL, CONVERTOR_REGISTER_NAME, "getConvertor", "(Ljava/lang/Class;Ljava/lang/Class;)L" + toInternalName(PARENT_ANME) + ";", false);
            } else {
                mv.visitMethodInsn(INVOKESTATIC, MAPPER_NAME, "instance", "()L" + MAPPER_NAME + ";", false);
                mv.visitLdcInsn(orignalCls.getName());
                mv.visitLdcInsn(cinfo.orignalInfo.field.getName());
                mv.visitMethodInsn(INVOKEVIRTUAL, MAPPER_NAME, "getConvertor", "(Ljava/lang/String;Ljava/lang/String;)L" + toInternalName(IFACE_NAME) + ";", false);
            }
            mv.visitFieldInsn(PUTSTATIC, toInternalName(convertorName), fieldConvertorName, "L" + IFACE_NAME + ";");
        }

        mv.visitInsn(RETURN);
        mv.visitMaxs(2, 0);
        mv.visitEnd();
    }

}