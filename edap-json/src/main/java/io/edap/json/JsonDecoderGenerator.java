package io.edap.json;

import io.edap.json.enums.DataType;
import io.edap.json.enums.JsonVersion;
import io.edap.json.model.ByteArrayDataRange;
import io.edap.json.model.DataRange;
import io.edap.json.model.JsonFieldInfo;
import io.edap.json.model.StringDataRange;
import io.edap.util.StringUtil;
import io.edap.util.internal.GeneratorClassInfo;
import org.objectweb.asm.*;

import java.io.IOException;
import java.lang.reflect.ParameterizedType;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import static io.edap.json.enums.JsonVersion.JSON;
import static io.edap.json.enums.JsonVersion.JSON5;
import static io.edap.json.util.JsonUtil.*;
import static io.edap.util.AsmUtil.*;
import static io.edap.util.ClazzUtil.getDescriptor;
import static org.objectweb.asm.Opcodes.*;

public class JsonDecoderGenerator {

    static final String IFACE_NAME = toInternalName(JsonDecoder.class.getName());
    static final String REGISTER_NAME = toInternalName(JsonCodecRegister.class.getName());
    static final String READER_NAME = toInternalName(JsonReader.class.getName());

    static final String DATARANGE_NAME = toInternalName(DataRange.class.getName());

    static final String PARENT_NAME = toInternalName(AbstractDecoder.class.getName());

    private List<GeneratorClassInfo> inners;

    private final List<JsonFieldInfo> arrayFields = new ArrayList<>();
    private final List<JsonFieldInfo> listFields = new ArrayList<>();
    private final List<JsonFieldInfo> mapFields = new ArrayList<>();
    private final List<JsonFieldInfo> stringFields = new ArrayList<>();

    private ClassWriter cw;
    private String pojoName;
    private String pojoDecoderName;
    private final Class pojoCls;
    private final DataType dataType;
    private final JsonVersion jsonVersion;

    public JsonDecoderGenerator(Class<?> pojoClass, DataType dataType) {
        this(pojoClass, dataType, JSON);
    }

    public JsonDecoderGenerator(Class<?> pojoClass, DataType dataType, JsonVersion jsonVersion) {
        this.pojoCls = pojoClass;
        this.dataType = dataType;
        this.jsonVersion = jsonVersion;
    }

    public GeneratorClassInfo getClassInfo() throws IOException {
        GeneratorClassInfo gci = new GeneratorClassInfo();

        pojoName = toInternalName(pojoCls.getName());
        pojoDecoderName = toInternalName(buildDecoderName(pojoCls, dataType, jsonVersion));
        gci.clazzName = pojoDecoderName;
        String[] ifaceName = new String[]{IFACE_NAME};
        String pojoCodecDescriptor = getDecoderDescriptor(pojoCls);
        inners = new ArrayList<>();

        List<JsonFieldInfo> fields = getCodecFieldInfos(pojoCls);

        List<java.lang.reflect.Type> pojoTypes = new ArrayList<>();
        DataRange dataRange;
        if (dataType == DataType.STRING) {
            dataRange = StringDataRange.from("a");
        } else {
            dataRange = ByteArrayDataRange.from("a");
        }
        for (JsonFieldInfo pfi : fields) {
            String jsonFieldName = pfi.jsonFieldName;
            if (StringUtil.isEmpty(jsonFieldName)) {
                jsonFieldName = pfi.field.getName();
            }
            pfi.jsonFieldHash = dataRange.keyHashCode(jsonFieldName);
            if (pfi.field.getType().getName().equals("java.lang.String")) {
                stringFields.add(pfi);
            }
            if (isPojo(pfi.field.getGenericType())
                    && !pojoTypes.contains(pfi.field.getGenericType())) {
                pojoTypes.add(pfi.field.getGenericType());
            } else if (isRepeatedArray(pfi.field.getGenericType())) {
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

        Collections.sort(fields, Comparator.comparing((JsonFieldInfo o) -> o.jsonFieldHash));

        cw = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        cw.visit(V1_8, ACC_PUBLIC + ACC_SUPER, pojoDecoderName,
                pojoCodecDescriptor, PARENT_NAME, ifaceName);

        //visitClinitMethod();
        visitInitMethod(pojoTypes, fields);
        visitDecodeMethod(fields);

        visitDecodeBridgeMethod();

        cw.visitEnd();
        gci.inners = inners;
        gci.clazzBytes = cw.toByteArray();
        return gci;
    }

    private void visitDecodeMethod(List<JsonFieldInfo> fields) {
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "decode",
                "(L" + READER_NAME + ";)L" + pojoName + ";",
                null, new String[] { "java/lang/reflect/InvocationTargetException",
                        "java/lang/InstantiationException", "java/lang/IllegalAccessException" });
        mv.visitCode();
        if (jsonVersion == JSON5) {
            mv.visitVarInsn(ALOAD, 1);
            mv.visitMethodInsn(INVOKEINTERFACE, READER_NAME, "readComment", "()Ljava/util/List;", true);
            mv.visitInsn(POP);
        }
        mv.visitVarInsn(ALOAD, 1);
        mv.visitMethodInsn(INVOKEINTERFACE, READER_NAME, "firstNotSpaceChar", "()C", true);
        mv.visitVarInsn(ISTORE, 2);
        mv.visitVarInsn(ILOAD, 2);
        mv.visitIntInsn(BIPUSH, '{');
        Label labelJsonStart = new Label();
        mv.visitJumpInsn(IF_ICMPEQ, labelJsonStart);
        mv.visitInsn(ACONST_NULL);
        mv.visitInsn(ARETURN);
        mv.visitLabel(labelJsonStart);
        mv.visitFrame(Opcodes.F_APPEND,1, new Object[] {Opcodes.INTEGER}, 0, null);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitInsn(ICONST_1);
        mv.visitMethodInsn(INVOKEINTERFACE, READER_NAME, "nextPos",
                "(I)V", true);
        if (jsonVersion == JSON5) {
            mv.visitVarInsn(ALOAD, 1);
            mv.visitMethodInsn(INVOKEINTERFACE, READER_NAME, "readComment", "()Ljava/util/List;", true);
            mv.visitInsn(POP);
        }
        mv.visitVarInsn(ALOAD, 1);
        mv.visitMethodInsn(INVOKEINTERFACE, READER_NAME, "firstNotSpaceChar",
                "()C", true);
        mv.visitVarInsn(ISTORE, 2);
        mv.visitTypeInsn(NEW, pojoName);
        mv.visitInsn(DUP);
        mv.visitMethodInsn(INVOKESPECIAL, pojoName, "<init>", "()V", false);
        mv.visitVarInsn(ASTORE, 3);
        mv.visitVarInsn(ILOAD, 2);
        mv.visitIntInsn(BIPUSH, '}');
        Label labelJsonEnd = new Label();
        mv.visitJumpInsn(IF_ICMPNE, labelJsonEnd);
        mv.visitVarInsn(ALOAD, 3);
        mv.visitInsn(ARETURN);
        mv.visitLabel(labelJsonEnd);
        mv.visitFrame(Opcodes.F_APPEND,1, new Object[] {pojoName}, 0, null);
        if (jsonVersion == JSON5) {
            mv.visitVarInsn(ALOAD, 1);
            mv.visitMethodInsn(INVOKEINTERFACE, READER_NAME, "readComment", "()Ljava/util/List;", true);
            mv.visitInsn(POP);
        }
        mv.visitVarInsn(ALOAD, 1);
        mv.visitMethodInsn(INVOKEINTERFACE, READER_NAME, "keyHash",
                "()I", true);
        mv.visitVarInsn(ISTORE, 4);
        mv.visitVarInsn(ILOAD, 4);
        Label[] lbFieldCases = new Label[fields.size()];
        int[] fieldHashCodes = new int[fields.size()];
        for (int i=0;i<fields.size();i++) {
            lbFieldCases[i] = new Label();
            fieldHashCodes[i] = fields.get(i).jsonFieldHash;
        }
        Label lbDefCase   = new Label();
        Label lbEndSwitch = new Label();
        mv.visitLookupSwitchInsn(lbDefCase, fieldHashCodes, lbFieldCases);
        for (int i=0;i<fields.size();i++) {
            JsonFieldInfo jfi = fields.get(i);
            mv.visitLabel(lbFieldCases[i]);
            if (isList(jfi.field.getGenericType())) {
                ParameterizedType ptype = (ParameterizedType)jfi.field.getGenericType();
                java.lang.reflect.Type itemType = ptype.getActualTypeArguments()[0];
                mv.visitVarInsn(ALOAD, 3);
                mv.visitVarInsn(ALOAD, 0);
                mv.visitVarInsn(ALOAD, 1);
                mv.visitLdcInsn(Type.getType(getDescriptor(itemType)));
                mv.visitMethodInsn(INVOKEVIRTUAL, pojoDecoderName, "readList",
                        "(L" + READER_NAME + ";Ljava/lang/Class;)Ljava/util/List;", false);
                visitSetValueOpcode(mv, jfi);
            } else {
                mv.visitVarInsn(ALOAD, 3);
                mv.visitVarInsn(ALOAD, 1);
                String readMethod = getReadMethod(jfi);
                mv.visitMethodInsn(INVOKEINTERFACE, READER_NAME, readMethod,
                        "()" + getDescriptor(jfi.field.getGenericType()), true);
//            mv.visitMethodInsn(INVOKEVIRTUAL, pojoName, "setField1",
//                    "(Ljava/lang/String;)V", false);
                visitSetValueOpcode(mv, jfi);
            }
            mv.visitJumpInsn(GOTO, lbEndSwitch);
        }

        mv.visitLabel(lbDefCase);
        mv.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitMethodInsn(INVOKEINTERFACE, READER_NAME, "skipValue",
                "()V", true);

        mv.visitLabel(lbEndSwitch);
        mv.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitMethodInsn(INVOKEINTERFACE, READER_NAME, "firstNotSpaceChar",
                "()C", true);
        mv.visitVarInsn(ISTORE, 2);

        Label lbWhile = new Label();
        mv.visitLabel(lbWhile);
        mv.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
        mv.visitVarInsn(ILOAD, 2);
        mv.visitIntInsn(BIPUSH, ',');

        Label lbJsonEnd = new Label();
        mv.visitJumpInsn(IF_ICMPNE, lbJsonEnd);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitInsn(ICONST_1);
        mv.visitMethodInsn(INVOKEINTERFACE, READER_NAME, "nextPos", "(I)V", true);
        mv.visitVarInsn(ALOAD, 1);
        if (jsonVersion == JSON5) {
            mv.visitVarInsn(ALOAD, 1);
            mv.visitMethodInsn(INVOKEINTERFACE, READER_NAME, "readComment", "()Ljava/util/List;", true);
            mv.visitInsn(POP);
        }
        mv.visitMethodInsn(INVOKEINTERFACE, READER_NAME, "keyHash",
                "()I", true);
        mv.visitVarInsn(ISTORE, 4);
        mv.visitVarInsn(ILOAD, 4);

        Label[] lbWhileCases = new Label[fields.size()];
        for (int i=0;i<fields.size();i++) {
            lbWhileCases[i] = new Label();
        }
        Label lbWhileDefCase = new Label();
        Label lbWhileSwitchEnd = new Label();

        mv.visitLookupSwitchInsn(lbWhileDefCase, fieldHashCodes, lbWhileCases);
        for (int i=0;i<fields.size();i++) {
            JsonFieldInfo jfi = fields.get(i);
            mv.visitLabel(lbWhileCases[i]);
            if (isList(jfi.field.getGenericType())) {
                ParameterizedType ptype = (ParameterizedType)jfi.field.getGenericType();
                java.lang.reflect.Type itemType = ptype.getActualTypeArguments()[0];
                mv.visitVarInsn(ALOAD, 3);
                mv.visitVarInsn(ALOAD, 0);
                mv.visitVarInsn(ALOAD, 1);
                mv.visitLdcInsn(Type.getType(getDescriptor(itemType)));
                mv.visitMethodInsn(INVOKEVIRTUAL, pojoDecoderName, "readList",
                        "(L" + READER_NAME + ";Ljava/lang/Class;)Ljava/util/List;", false);
                visitSetValueOpcode(mv, jfi);
            } else {
                mv.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
                mv.visitVarInsn(ALOAD, 3);
                mv.visitVarInsn(ALOAD, 1);
                String readMethod = getReadMethod(jfi);
                mv.visitMethodInsn(INVOKEINTERFACE, READER_NAME, readMethod,
                        "()" + getDescriptor(jfi.field.getGenericType()), true);
                //mv.visitMethodInsn(INVOKEVIRTUAL, pojoName, "setField1",
                //        "(Ljava/lang/String;)V", false);
                visitSetValueOpcode(mv, fields.get(i));
            }
            mv.visitJumpInsn(GOTO, lbWhileSwitchEnd);
        }

        mv.visitLabel(lbWhileDefCase);
        mv.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitMethodInsn(INVOKEINTERFACE, READER_NAME, "skipValue",
                "()V", true);

        mv.visitLabel(lbWhileSwitchEnd);
        mv.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
        if (jsonVersion == JSON5) {
            mv.visitVarInsn(ALOAD, 1);
            mv.visitMethodInsn(INVOKEINTERFACE, READER_NAME, "readComment", "()Ljava/util/List;", true);
            mv.visitInsn(POP);
        }
        mv.visitVarInsn(ALOAD, 1);
        mv.visitMethodInsn(INVOKEINTERFACE, READER_NAME, "firstNotSpaceChar",
                "()C", true);
        mv.visitVarInsn(ISTORE, 2);
        mv.visitJumpInsn(GOTO, lbWhile);

        mv.visitLabel(lbJsonEnd);
        mv.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
        mv.visitVarInsn(ILOAD, 2);
        mv.visitIntInsn(BIPUSH, '}');
        Label lbPojoRetrun = new Label();
        mv.visitJumpInsn(IF_ICMPEQ, lbPojoRetrun);
        mv.visitTypeInsn(NEW, "io/edap/json/JsonParseException");
        mv.visitInsn(DUP);
        mv.visitTypeInsn(NEW, "java/lang/StringBuilder");
        mv.visitInsn(DUP);
        mv.visitMethodInsn(INVOKESPECIAL, "java/lang/StringBuilder", "<init>",
                "()V", false);
        mv.visitLdcInsn("key and value \u540e\u4e3a\u4e0d\u7b26\u5408json\u5b57\u7b26[");
        mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/StringBuilder", "append",
                "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false);
        mv.visitVarInsn(ILOAD, 2);
        mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/StringBuilder", "append",
                "(C)Ljava/lang/StringBuilder;", false);
        mv.visitLdcInsn("]");
        mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/StringBuilder", "append",
                "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false);
        mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/StringBuilder", "toString",
                "()Ljava/lang/String;", false);
        mv.visitMethodInsn(INVOKESPECIAL, "io/edap/json/JsonParseException", "<init>",
                "(Ljava/lang/String;)V", false);
        mv.visitInsn(ATHROW);

        mv.visitLabel(lbPojoRetrun);
        mv.visitFrame(Opcodes.F_SAME, 0, null, 0, null);

        mv.visitVarInsn(ALOAD, 1);
        mv.visitInsn(ICONST_1);
        mv.visitMethodInsn(INVOKEINTERFACE, READER_NAME, "nextPos", "(I)V", true);

        mv.visitVarInsn(ALOAD, 3);
        mv.visitInsn(ARETURN);
        mv.visitMaxs(4, 5);
        mv.visitEnd();
    }

    private void visitDecodeBridgeMethod() {
        MethodVisitor mv;
        mv = cw.visitMethod(ACC_PUBLIC + ACC_BRIDGE + ACC_SYNTHETIC, "decode",
                "(L" + READER_NAME + ";)Ljava/lang/Object;", null, null);

        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitVarInsn(ALOAD, 1);
        visitMethod(mv, INVOKEVIRTUAL, pojoDecoderName,
                "decode", "(L" + READER_NAME + ";)L"
                        + toInternalName(pojoCls.getName())
                        + ";", false);
        mv.visitInsn(ARETURN);
        mv.visitMaxs(2, 2);
        mv.visitEnd();
    }

    private void visitInitMethod(List<java.lang.reflect.Type> pojoTypes, List<JsonFieldInfo> fields) {
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKESPECIAL, PARENT_NAME, "<init>", "()V", false);
        mv.visitInsn(RETURN);
        mv.visitMaxs(1, 1);
        mv.visitEnd();
    }

    private void visitSetValueOpcode(MethodVisitor mv, JsonFieldInfo pfi) {
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

    private void visitClinitMethod() {
        FieldVisitor fv;
        MethodVisitor mv;
        mv = cw.visitMethod(ACC_STATIC, "<clinit>", "()V", null, null);
        mv.visitCode();

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
}
