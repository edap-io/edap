package io.edap.eproto;

import io.edap.eproto.util.EprotoUtil;
import io.edap.protobuf.EncodeException;
import io.edap.protobuf.ProtoFieldInfo;
import io.edap.protobuf.wire.Field;
import io.edap.util.ClazzUtil;
import io.edap.util.CollectionUtils;
import io.edap.util.internal.GeneratorClassInfo;
import org.objectweb.asm.ClassWriter;

import java.io.IOException;
import java.lang.reflect.ParameterizedType;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

import static io.edap.util.AsmUtil.*;
import static io.edap.util.AsmUtil.isIterable;
import static io.edap.util.ClazzUtil.getDescriptor;
import static org.objectweb.asm.Opcodes.*;

public class EprotoEncoderGenerator {

    static final String IFACE_NAME = toInternalName(EprotoEncoder.class.getName());
    static final String WRITER_NAME = toInternalName(EprotoWriter.class.getName());
    static final String REGISTER_NAME = toInternalName(EprotoCodecRegister.class.getName());
    static final String COLLECTION_UTIL = toInternalName(CollectionUtils.class.getName());
    static final String FIELD_TYPE_NAME = toInternalName(Field.Type.class.getName());
    static final String PROTOUTIL_NAME = toInternalName(EprotoUtil.class.getName());
    static final String CARDINALITY_NAME = toInternalName(Field.Cardinality.class.getName());
    static final String CLAZZ_UTIL_NAME = toInternalName(ClazzUtil.class.getName());
    static final String ENCODE_EX_NAME = toInternalName(EncodeException.class.getName());

    private static final String WRITE_MAP_PREFIX = "writeMap_";
    private static final String WRITE_LIST_PREFIX = "writeList_";
    private static final String WRITE_ARRAY_PREFIX = "writeArray_";
    private static final String WRITE_ITERATOR_PREFIX = "writeIterator_";
    private final HashSet codecNames = new HashSet();
    private final HashSet codecMethods = new HashSet();

    private ClassWriter cw;
    private final Class pojoCls;

    private List<GeneratorClassInfo> inners;
    private String parentName;
    private String pojoName;
    private String pojoCodecName;

    private final List<ProtoFieldInfo> arrayFields = new ArrayList<>();
    private final List<ProtoFieldInfo> listFields = new ArrayList<>();
    private final List<ProtoFieldInfo> mapFields = new ArrayList<>();
    private final List<ProtoFieldInfo> iterableFields = new ArrayList<>();

    private final List<String> listMethods = new ArrayList<>();
    private final List<String> arrayMethods = new ArrayList<>();
    private final List<String> mapMethods = new ArrayList<>();
    private final List<String> iteratorMethods = new ArrayList<>();

    private final java.lang.reflect.Type parentMapType;

    public EprotoEncoderGenerator(Class clazz) {
        this.pojoCls = clazz;
        this.parentMapType = EprotoUtil.parentMapType(pojoCls);
    }

    public GeneratorClassInfo getClassInfo() throws IOException {
        GeneratorClassInfo gci = new GeneratorClassInfo();

        inners = new ArrayList<>();
        pojoName = toInternalName(pojoCls.getName());
        parentName = toInternalName(AbstractEncoder.class.getName());
        pojoCodecName = toInternalName(getEncoderName(pojoCls));
        gci.clazzName = pojoCodecName;
        String pojoCodecDescriptor = getEncoderDescriptor(pojoCls);
        String[] ifaceName = new String[]{IFACE_NAME};

        //定义编码器名称，继承的虚拟编码器以及实现的接口
        cw = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        cw.visit(V1_8, ACC_PUBLIC + ACC_SUPER, pojoCodecName,
                pojoCodecDescriptor, parentName, ifaceName);


        //List<ProtoFieldInfo> fields = ProtoUtil.getProtoFields(pojoCls);
        List<ProtoFieldInfo> fields = new ArrayList<>();
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
//                List<java.lang.reflect.Type> itemTypes =
//                        getAllPojoTypes(pfi.field.getGenericType());
                List<java.lang.reflect.Type> itemTypes = new ArrayList<>();
                for (java.lang.reflect.Type t : itemTypes) {
                    if (!pojoTypes.contains(t)) {
                        pojoTypes.add(t);
                    }
                }
                if (isList(pfi.field.getGenericType())) {
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
//        visitClinitMethod(fields);
//        visitInitMethod(pojoTypes, fields);
//        visitGetEncoderMethods(pojoTypes);
//
//        visitEncodeMethod(fields);
//
//        visitEncodeBridgeMethod();

        cw.visitEnd();
        gci.inners = inners;
        gci.clazzBytes = cw.toByteArray();

        return gci;
    }

    private static String getEncoderDescriptor(Class msgCls) {
        StringBuilder sb = new StringBuilder();
        sb.append(getDescriptor(AbstractEncoder.class));
        sb.append("L").append(IFACE_NAME).append("<");
        sb.append(getDescriptor(msgCls));
        sb.append(">;");
        return sb.toString();
    }

    static String getEncoderName(Class pojoCls) {
        if (pojoCls == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder("epe");

        sb.append('.');
        sb.append(pojoCls.getName());
        sb.append("Encoder");
        return sb.toString();
    }
}
