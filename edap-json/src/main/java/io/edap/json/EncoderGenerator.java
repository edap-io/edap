package io.edap.json;

import io.edap.json.model.JsonFieldInfo;
import io.edap.json.util.JsonUtil;
import io.edap.util.CollectionUtils;
import io.edap.util.internal.GeneratorClassInfo;
import org.objectweb.asm.*;

import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.*;

import static io.edap.json.util.JsonUtil.*;
import static io.edap.util.AsmUtil.*;
import static io.edap.util.ClazzUtil.getDescriptor;
import static org.objectweb.asm.ClassWriter.COMPUTE_FRAMES;
import static org.objectweb.asm.ClassWriter.COMPUTE_MAXS;
import static org.objectweb.asm.Opcodes.*;

public class EncoderGenerator {

    private static final String IFACE_NAME = toInternalName(JsonEncoder.class.getName());
    private static final String PARENT_NAME = toInternalName(AbstractEncoder.class.getName());
    private static final String WRITER_NAME = toInternalName(JsonWriter.class.getName());
    private static final String REGISTER_NAME = toInternalName(JsonCodecRegister.class.getName());

    private static final String FIELD_BYTES_PREFIX = "KBS_";
    private static final String ENCODER_PREFIX     = "ENCODER_";

    private final List<Type> codecNames = new ArrayList<>();
    //枚举类型对应枚举名称数组的对应关系
    private final Map<String, String> enumNames = new HashMap<>();

    private final Class pojoCls;
    private final String encoderName;
    private final String pojoName;
    private ClassWriter cw;

    private       List<JsonFieldInfo> enumFields;
    private List<JsonFieldInfo> encodeFields;
    private List<JsonFieldInfo> mapFields;
    private final int varWriter = 1;
    private final int varPojo   = 2;
    
    public <T extends Object> EncoderGenerator(Class<T> msgCls) {
        this.pojoCls     = msgCls;
        this.encoderName = toInternalName(buildEncoderName(pojoCls));
        this.pojoName    = toInternalName(pojoCls.getName());
        this.mapFields  = new ArrayList<>();
    }

    private static String getEncoderDescriptor(Class msgCls) {
        StringBuilder sb = new StringBuilder();
        sb.append(getDescriptor(AbstractEncoder.class));
        sb.append("L").append(IFACE_NAME).append("<");
        sb.append(getDescriptor(msgCls));
        sb.append(">;");
        return sb.toString();
    }

    public GeneratorClassInfo getClassInfo() {
        GeneratorClassInfo gci = new GeneratorClassInfo();
        String msgEncoderDescriptor = getEncoderDescriptor(pojoCls);
        String[] ifaceName = new String[]{IFACE_NAME};
        //定义编码器名称以及实现的接口
        cw = new ClassWriter(COMPUTE_MAXS | COMPUTE_FRAMES);
        cw.visit(V1_8, ACC_PUBLIC + ACC_SUPER, encoderName,
                msgEncoderDescriptor, PARENT_NAME, ifaceName);

        encodeFields = JsonUtil.getCodecFieldInfos(pojoCls);
        enumFields = new ArrayList<>();
        encodeFields.forEach(e -> {
            if (isEnum(e.field.getGenericType())) {
                String enumName = toInternalName(e.field.getType().getName());

                if (!enumNames.containsKey(enumName)) {
                    String varName = "ENUM_NAME_" + enumNames.size();
                    enumNames.put(enumName, varName);
                    enumFields.add(e);
                }
            }
            if (e.isMap) {
                mapFields.add(e);
            }
        });
        encodeFields.sort((jfi1, jfi2) -> {
            if (isBaseType(jfi1.field) && !isBaseType(jfi2.field)) {
                return -1;
            } else if (isBaseType(jfi1.field) && isBaseType(jfi2.field)) {
                 return jfi1.field.getName().compareTo(jfi2.field.getName());
            } else {
                return 1;
            }
        });
        List<Type> allPojos = new ArrayList<>();
        //声明变量以及初始化函数
        visitInit(encodeFields, allPojos);
        //初始化static的变量
        visitClInit(encodeFields, allPojos);
        // 创建encode的方法体
        visitEncodeMethod();

        //构建encode的Bridge方法的字节码
        visitEncodeBridge();

        gci.clazzName = encoderName;
        gci.clazzBytes = cw.toByteArray();
        return gci;
    }

    /**
     * 构建JSON编码逻辑的字节码
     */
    private void visitEncodeMethod() {
        MethodVisitor mv;
        mv = cw.visitMethod(ACC_PUBLIC, "encode",
                "(L" + WRITER_NAME + ";L" + pojoName + ";)V", null, null);
        mv.visitCode();

        // writer.writeByte((byte)'{');
        mv.visitVarInsn(ALOAD, 1);
        mv.visitIntInsn(BIPUSH, 123);
        mv.visitMethodInsn(INVOKEINTERFACE, WRITER_NAME, "write", "(B)V", true);

        //int varOffset = 3;
        // int offset = 1;
        mv.visitInsn(ICONST_1);
        mv.visitVarInsn(ISTORE, 3);

        // 循环属性
        boolean hasWrite = false; // 是否已经有写入数据，如果有基本类型写就会有输出
        for (int i=0;i<encodeFields.size();i++) {
            JsonFieldInfo jfi = encodeFields.get(i);
            String typeString = getDescriptor(jfi.field.getType());
            Label l0 = null;
            if (!isBaseType(jfi.field)) {
                mv.visitVarInsn(ALOAD, 2);
                if (jfi.method != null) {
                    visitMethod(mv, INVOKEVIRTUAL, pojoName, jfi.method.getName(), "()" + typeString, false);
                } else {
                    mv.visitFieldInsn(GETFIELD, pojoName, jfi.field.getName(), typeString);
                }
                l0 = new Label();
                mv.visitJumpInsn(IFNULL, l0);
            }
            mv.visitVarInsn(ALOAD, 1);
            mv.visitFieldInsn(GETSTATIC, encoderName, "KBS_" + jfi.field.getName().toUpperCase(Locale.ENGLISH), "[B");
            //mv.visitLdcInsn(",\"" + jfi.field.getName() + "\":");
            mv.visitVarInsn(ILOAD, 3);
            mv.visitIntInsn(BIPUSH, jfi.field.getName().length() + 4);
            mv.visitMethodInsn(INVOKEINTERFACE, WRITER_NAME, "writeField", "([BII)V", true);

            if (jfi.isMap) {
                mv.visitFieldInsn(GETSTATIC, encoderName,  ENCODER_PREFIX+ jfi.field.getName().toUpperCase(Locale.ENGLISH),
                        "L" + IFACE_NAME + ";");
                mv.visitVarInsn(ALOAD, 1);
                mv.visitVarInsn(ALOAD, 2);
                if (jfi.method != null) {
                    visitMethod(mv, INVOKEVIRTUAL, pojoName, jfi.method.getName(), "()" + typeString, false);
                } else {
                    mv.visitFieldInsn(GETFIELD, pojoName, jfi.field.getName(), typeString);
                }
                visitMethod(mv, INVOKEINTERFACE, IFACE_NAME, "encode", "(L" + WRITER_NAME + ";Ljava/lang/Object;)V", true);
            } else {
                mv.visitVarInsn(ALOAD, 1);
                mv.visitVarInsn(ALOAD, 2);
                if (jfi.method != null) {
                    visitMethod(mv, INVOKEVIRTUAL, pojoName, jfi.method.getName(), "()" + typeString, false);
                } else {
                    mv.visitFieldInsn(GETFIELD, pojoName, jfi.field.getName(), typeString);
                }
                String writeMethod = getWriteMethod(jfi.field);
                visitMethod(mv, INVOKEINTERFACE, WRITER_NAME, writeMethod, "(" + typeString + ")V", true);
            }
            if (!isBaseType(jfi.field)) {
                if (!hasWrite) {
                    mv.visitInsn(ICONST_0);
                    mv.visitVarInsn(ISTORE, 3);
                }
                mv.visitLabel(l0);
            } else {
                if (!hasWrite) {
                    hasWrite = true;
                    mv.visitInsn(ICONST_0);
                    mv.visitVarInsn(ISTORE, 3);
                }
            }
        }

        mv.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
        // write.write((byte)'}')
        mv.visitVarInsn(ALOAD, 1);
        mv.visitIntInsn(BIPUSH, 125);
        mv.visitMethodInsn(INVOKEINTERFACE, WRITER_NAME, "write", "(B)V", true);
        mv.visitInsn(RETURN);
        mv.visitMaxs(4, 4);
        mv.visitEnd();


//        // 循环属性
//        boolean hasWrite = false; // 是否已经有写入数据，如果有基本类型写就会有输出
//        for (int i=0;i<encodeFields.size();i++) {
//            if (i > 0) {
//                mv.visitFrame(Opcodes.F_APPEND,1, new Object[] {Opcodes.INTEGER}, 0, null);
//            }
//            JsonFieldInfo jfi = encodeFields.get(i);
//            Label l = null;
//            String typeString = getDescriptor(jfi.field.getType());
//            int duo = hasWrite?4:3;
//            if (!isBaseType(jfi.field)) {
//                if (jfi.method != null) {
//                    visitMethod(mv, INVOKEVIRTUAL, pojoName, jfi.method.getName(), "()" + typeString, false);
//                } else {
//                    mv.visitFieldInsn(GETFIELD, pojoName, jfi.field.getName(), typeString);
//                }
//                l = new Label();
//                mv.visitJumpInsn(IFNULL, l);
//            }
//
//            mv.visitVarInsn(ALOAD, 1);
//            mv.visitFieldInsn(GETSTATIC, encoderName, "KBS_" + jfi.field.getName(), "[B");
//            mv.visitVarInsn(ILOAD, varOffset);
//            mv.visitIntInsn(BIPUSH, jfi.field.getName().length() + duo);
//            mv.visitMethodInsn(INVOKEINTERFACE, WRITER_NAME, "writeBytes", "([BII)V", true);
//            mv.visitVarInsn(ALOAD, 1);
//            mv.visitVarInsn(ALOAD, 2);
//            if (jfi.method != null) {
//                visitMethod(mv, INVOKEVIRTUAL, pojoName, jfi.method.getName(), "()" + typeString, false);
//            } else {
//                mv.visitFieldInsn(GETFIELD, pojoName, jfi.field.getName(), typeString);
//            }
//            String writeMethod = getWriteMethod(jfi.field);
//            visitMethod(mv, INVOKEINTERFACE, WRITER_NAME, writeMethod, "(" + typeString + ")V", true);
//            // offset = 0;
//            if (!isBaseType(jfi.field)) {
//                mv.visitInsn(ICONST_0);
//                mv.visitVarInsn(ISTORE, varOffset);
//            } else {
//                if (duo == 3) {
//                    mv.visitInsn(ICONST_0);
//                    mv.visitVarInsn(ISTORE, varOffset);
//                    hasWrite = true;
//                }
//            }
//
//            if (!isBaseType(jfi.field)) {
//                mv.visitLabel(l);
//            }
//        }
//
//        // write.write((byte)'}')
//        mv.visitVarInsn(ALOAD, 1);
//        mv.visitIntInsn(BIPUSH, 125);
//        mv.visitMethodInsn(INVOKEINTERFACE, WRITER_NAME, "writeByte", "(B)V", true);
//        mv.visitInsn(RETURN);
//        mv.visitMaxs(4, 4);
//        mv.visitEnd();

    }

    private void visitClInit(List<JsonFieldInfo> fields, List<Type> allPojos) {

        if (!CollectionUtils.isEmpty(mapFields)) {
            for (int i=0;i<mapFields.size();i++) {
                JsonFieldInfo jfi = mapFields.get(i);
                FieldVisitor fv = cw.visitField(ACC_PRIVATE + ACC_FINAL + ACC_STATIC,
                        ENCODER_PREFIX + jfi.field.getName().toUpperCase(Locale.ENGLISH),
                        "L" + IFACE_NAME + ";", "L" + IFACE_NAME + ";", null);
                fv.visitEnd();
            }
        }

        MethodVisitor mv;
        mv = cw.visitMethod(ACC_STATIC, "<clinit>", "()V", null, null);
        mv.visitCode();
        Label l0 = null;
        Label l1 = null;
        Label l2 = null;
        if (!CollectionUtils.isEmpty(mapFields)) {
            l0 = new Label();
            l1 = new Label();
            l2 = new Label();
            mv.visitTryCatchBlock(l0, l1, l2, "java/lang/NoSuchFieldException");
        }

        //为Field的名称char[]数组赋值赋值
        fields.forEach(e -> {
            assignKeyCharsVal(mv, e.field);

        });

        //为属性以及泛型是POJO的类型赋值JSON编码器
        allPojos.forEach(t -> assignPojoEncoders(mv, t));


        enumFields.forEach(e -> {
            String enumName = toInternalName(e.field.getType().getName());
        });

        if (!CollectionUtils.isEmpty(mapFields)) {
            for (int i=0;i<mapFields.size();i++) {
                JsonFieldInfo jfi = mapFields.get(i);
                mv.visitLabel(l0);
                mv.visitLdcInsn(org.objectweb.asm.Type.getType("L" + pojoName + ";"));
                mv.visitLdcInsn("methods");
                mv.visitMethodInsn(INVOKESTATIC, "io/edap/x/util/ClazzUtil", "getField", "(Ljava/lang/Class;Ljava/lang/String;)Ljava/lang/reflect/Field;", false);
                mv.visitVarInsn(ASTORE, 2);
                mv.visitFieldInsn(GETSTATIC, REGISTER_NAME, "INSTANCE", "L" + REGISTER_NAME + ";");
                mv.visitVarInsn(ALOAD, 2);
                mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/reflect/Field", "getGenericType", "()Ljava/lang/reflect/Type;", false);
                mv.visitMethodInsn(INVOKEVIRTUAL, REGISTER_NAME, "getMapEncoder",
                        "(Ljava/lang/reflect/Type;)L" + IFACE_NAME + ";", false);
                mv.visitVarInsn(ASTORE, i);
            }

            mv.visitLabel(l1);
            Label l3 = new Label();
            mv.visitJumpInsn(GOTO, l3);
            mv.visitLabel(l2);
            mv.visitFrame(Opcodes.F_SAME1, 0, null, 1, new Object[] {"java/lang/NoSuchFieldException"});
            mv.visitVarInsn(ASTORE, mapFields.size());
            mv.visitVarInsn(ALOAD, mapFields.size());
            mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/NoSuchFieldException", "printStackTrace", "()V", false);
            for (int i=0;i<mapFields.size();i++) {
                mv.visitInsn(ACONST_NULL);
                mv.visitVarInsn(ASTORE, i);
            }

            mv.visitLabel(l3);
            Object[] ifaceNames = new Object[mapFields.size()];
            for (int i=0;i<mapFields.size();i++) {
                ifaceNames[i] = IFACE_NAME;
            }
            mv.visitFrame(Opcodes.F_APPEND,mapFields.size(), ifaceNames, 0, null);
            for (int i=0;i<mapFields.size();i++) {
                JsonFieldInfo jfi = mapFields.get(i);
                mv.visitVarInsn(ALOAD, i);
                mv.visitFieldInsn(PUTSTATIC, encoderName,
                        ENCODER_PREFIX + jfi.field.getName().toUpperCase(Locale.ENGLISH), "L" + IFACE_NAME + ";");
            }
        }


        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    private void visitMapEncoder(MethodVisitor mv) {

    }

    /**
     * 为POJO属性为java对象编解码器的变量赋值
     * @param mv clinit的MethodVisitor对象
     * @param type 对象属性POJO的类型对象
     */
    private void assignPojoEncoders(MethodVisitor mv, Type type) {
        String pojoCodecName = getCodecFieldName(type);
        String pojoType = getDescriptor(type);
        mv.visitFieldInsn(GETSTATIC, REGISTER_NAME, "INSTANCE",
                "L" + REGISTER_NAME + ";");
        mv.visitLdcInsn(org.objectweb.asm.Type.getType(pojoType));
        visitMethod(mv, INVOKEVIRTUAL, REGISTER_NAME, "getEncoder",
                "(Ljava/lang/Class;)L" + IFACE_NAME + ";", false);
        mv.visitFieldInsn(PUTSTATIC, encoderName, pojoCodecName,
                "L" + IFACE_NAME + ";");
    }

    /**
     * 根据对象的Type类型获取该类型对象的JSONCodec的名称
     * @param type 对象类型类型
     * @return
     */
    private String getCodecFieldName(Type type) {
        int index = codecNames.indexOf(type);
        if (index < 0) {
            index = codecNames.size();
            codecNames.add(type);
        }
        return ENCODER_PREFIX + index;
    }

    /**
     * 为类的Field缓存的char[]赋值，方便序列化时直接copy数组而不用再从String转成char[]然后在
     * 赋值，加快Field名称的序列化速度
     * @param mv clinit的MethodVisitor对象
     * @param field 反射的Field对象
     */
    private void assignKeyCharsVal(MethodVisitor mv, Field field) {
        String fieldName = getFieldBytesVarName(field);
        mv.visitLdcInsn(",\"" + field.getName() + "\":null");
        visitMethod(mv, INVOKEVIRTUAL, "java/lang/String", "getBytes",
                "()[B", false);
        mv.visitFieldInsn(PUTSTATIC, encoderName, fieldName, "[B");
    }

    /**
     * 根据需要序列化的字段信息声明内部的一些变量，并生成初始化函数初始化内部变量
     * @param fields
     */
    private void visitInit(List<JsonFieldInfo> fields, List<Type> allPojos) {

        //定义编码器内部变量
        fields.forEach(e -> {
            defineKeyBytesVar(e.field);
            definePojoEncoders(e.field, allPojos);
        });

        for (int i=0;i<enumFields.size();i++) {
            JsonFieldInfo cfi = enumFields.get(i);
            FieldVisitor fv;
            String enumName = toInternalName(cfi.field.getType().getName());
            fv = cw.visitField(ACC_PRIVATE + ACC_FINAL + ACC_STATIC,
                    enumNames.get(enumName), "[[B", null, null);
            fv.visitEnd();
        }

        //初始化函数
        MethodVisitor mv;
        mv = cw.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        visitMethod(mv, INVOKESPECIAL, PARENT_NAME, "<init>", "()V", false);
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    private void definePojoEncoders(Field field, List<Type> allPojos) {
        List<Type> pojos = getPojoList(field.getGenericType());
        if (CollectionUtils.isEmpty(pojos)) {
            return;
        }
        pojos.forEach(t -> {
            if (!allPojos.contains(t)) {
                allPojos.add(t);
            }
            int codecsIndex = codecNames.indexOf(t);
            if (codecsIndex < 0) {
                codecsIndex = codecNames.size();
                String fname = ENCODER_PREFIX + codecsIndex;
                String encoderName = "L" + IFACE_NAME + ";";
                String encoderDescriptor = "L" + IFACE_NAME +
                        "<L" + toInternalName(((Class)t).getName()) + ";>;";
                FieldVisitor fv = cw.visitField(ACC_PRIVATE + ACC_FINAL
                        + ACC_STATIC, fname, encoderName, encoderDescriptor, null);
                fv.visitEnd();
                codecNames.add(t);
            }
        });
    }

    private List<Type> getPojoList(Type type) {
        List<Type> pojos = new ArrayList<>();
        if (type instanceof ParameterizedType) {
            ParameterizedType pt = (ParameterizedType)type;
            if (pt.getActualTypeArguments() != null
                    && pt.getActualTypeArguments().length > 0) {
                for (Type t : pt.getActualTypeArguments()) {
                    pojos.addAll(getPojoList(t));
                }
            }
        } else {
            if (isPojo(type) && !pojos.contains(type)) {
                pojos.add(type);
            }
        }
        return pojos;
    }

    private void defineKeyBytesVar(Field field) {
        String fieldBsName = getFieldBytesVarName(field);
        FieldVisitor fv = cw.visitField(ACC_PRIVATE + ACC_FINAL + ACC_STATIC,
                fieldBsName, "[B", null, null);
        fv.visitEnd();
    }

    /**
     * 根据发射的Field的对象获取该Field名称对应的char[]数组变量名称
     * @param field 反射的Field对象
     * @return
     */
    private String getFieldBytesVarName(Field field) {
        return FIELD_BYTES_PREFIX + field.getName().toUpperCase(Locale.ENGLISH);
    }

    private void visitEncodeBridge() {
        MethodVisitor mv;
        mv = cw.visitMethod(ACC_PUBLIC + ACC_BRIDGE + ACC_SYNTHETIC, "encode",
                "(L" + WRITER_NAME + ";Ljava/lang/Object;)V", null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitVarInsn(ALOAD, 2);
        mv.visitTypeInsn(CHECKCAST, pojoName);
        visitMethod(mv, INVOKEVIRTUAL, encoderName, "encode",
                "(L" + WRITER_NAME + ";L" + pojoName + ";)V", false);
        mv.visitInsn(RETURN);
        mv.visitMaxs(3, 3);
        mv.visitEnd();
    }

    /**
     * 根据POJO的类名获取该类的JSON编码器名称
     * @return
     */
    public String getEncoderName() {
        return encoderName;
    }
}
