package io.edap.json;

import io.edap.json.model.JsonFieldInfo;
import io.edap.json.util.JsonUtil;
import io.edap.util.internal.GeneratorClassInfo;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;

import java.io.IOException;
import java.lang.reflect.ParameterizedType;
import java.util.ArrayList;
import java.util.List;

import static io.edap.json.util.JsonUtil.*;
import static io.edap.util.AsmUtil.*;
import static io.edap.util.ClazzUtil.getDescriptor;
import static org.objectweb.asm.Opcodes.*;

public class JsonDecoderGenerator {

    static final String IFACE_NAME = toInternalName(JsonDecoder.class.getName());
    static final String REGISTER_NAME = toInternalName(JsonCodecRegister.class.getName());
    static final String READER_NAME = toInternalName(JsonReader.class.getName());

    static final String PARENT_NAME = toInternalName(AbstractDecoder.class.getName());

    private List<GeneratorClassInfo> inners;

    private final List<JsonFieldInfo> arrayFields = new ArrayList<>();
    private final List<JsonFieldInfo> listFields = new ArrayList<>();
    private final List<JsonFieldInfo> mapFields = new ArrayList<>();
    private final List<JsonFieldInfo> stringFields = new ArrayList<>();

    private ClassWriter cw;
    private String pojoName;
    String pojoDecoderName;
    private final Class pojoCls;

    public JsonDecoderGenerator(Class<?> pojoClass) {
        this.pojoCls = pojoClass;
    }

    public GeneratorClassInfo getClassInfo() throws IOException {
        GeneratorClassInfo gci = new GeneratorClassInfo();

        pojoName = toInternalName(pojoCls.getName());
        pojoDecoderName = toInternalName(getDecoderName(pojoCls));
        gci.clazzName = pojoDecoderName;
        String[] ifaceName = new String[]{IFACE_NAME};
        String pojoCodecDescriptor = getDecoderDescriptor(pojoCls);
        inners = new ArrayList<>();

        cw = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        cw.visit(V1_8, ACC_PUBLIC + ACC_SUPER, pojoDecoderName,
                pojoCodecDescriptor, PARENT_NAME, ifaceName);

        List<JsonFieldInfo> fields = getCodecFieldInfos(pojoCls);

        List<java.lang.reflect.Type> pojoTypes = new ArrayList<>();
        for (JsonFieldInfo pfi : fields) {
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

        //visitClinitMethod();
        visitInitMethod(pojoTypes, fields);

        visitDecodeBridgeMethod();

        cw.visitEnd();
        gci.inners = inners;
        gci.clazzBytes = cw.toByteArray();

        return gci;
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
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    private void visitInitMethod(List<java.lang.reflect.Type> pojoTypes, List<JsonFieldInfo> fields) {
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        mv.visitInsn(RETURN);
        mv.visitMaxs(1, 1);
        mv.visitEnd();
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
