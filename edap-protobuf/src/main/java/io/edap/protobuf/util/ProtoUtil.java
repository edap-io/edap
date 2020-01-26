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

package io.edap.protobuf.util;

import io.edap.protobuf.ProtoBuf.ProtoFieldInfo;
import io.edap.protobuf.annotation.ProtoField;
import io.edap.protobuf.internal.PbField;
import io.edap.protobuf.wire.Field.Cardinality;
import io.edap.protobuf.wire.Field.Type;
import io.edap.protobuf.wire.Syntax;
import io.edap.protobuf.wire.WireFormat;
import io.edap.protobuf.wire.WireType;
import io.edap.util.AsmUtil;
import org.objectweb.asm.MethodVisitor;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static io.edap.util.AsmUtil.*;
import static io.edap.util.ClazzUtil.*;

public class ProtoUtil {

    private ProtoUtil() {}

    public static boolean implInterface(Class type, Class iface) {
        Class<?>[] ifaces = type.getInterfaces();
        for (Class<?> cls : ifaces) {
            if (cls.getName() == iface.getName()) {
                return true;
            }
        }
        return false;
    }

    public static boolean isPacked(ProtoFieldInfo pfi) {
        return pfi.protoField.type().packable();
    }

    public static List<ProtoFieldInfo> getProtoFields(Class pojoClass) {
        List<ProtoFieldInfo> profields = new ArrayList<>();
        List<Field> fields = getClassFields(pojoClass);
        List<Method> methods = getClassMethods(pojoClass);
        Map<String, Method> aMethod = new HashMap<>();
        for (Method m : methods) {
            aMethod.put(m.getName(), m);
        }

        boolean hasProtoAnn = false;
        for (Field f : fields) {
            if (needEncode(f)) {
                ProtoFieldInfo pfi = new ProtoFieldInfo();
                pfi.field = PbField.from(f);
                Method em = getAccessMethod(f, aMethod);
                pfi.getMethod = em;
                Method setMethod = getSetMethod(f, aMethod);
                if (setMethod != null) {
                    pfi.setMethod = setMethod;
                }
                pfi.hasGetAccessed = Modifier.isPublic(f.getModifiers()) || pfi.getMethod != null;
                pfi.hasSetAccessed = Modifier.isPublic(f.getModifiers()) || pfi.setMethod != null;
                ProtoField pf = getProtoAnnotation(f, em);
                if (pf != null) {
                    pfi.protoField = pf;
                    hasProtoAnn = true;
                }
                profields.add(pfi);
            }
        }
        if (!hasProtoAnn) {
            generateProtoField(profields);
        }

        return profields;
    }

    public static byte[] buildFieldData(int tag, io.edap.protobuf.wire.Field.Type type, io.edap.protobuf.wire.Field.Cardinality cardinality) {
        return buildFieldData(tag, type, cardinality, Syntax.PROTO_3, null);
    }

    public static byte[] buildFieldData(int tag, Type type, Cardinality cardinality, Syntax syntax) {
        return buildFieldData(tag, type, cardinality, syntax, null);
    }

    public static byte[] buildFieldData(int tag, Type type, Cardinality cardinality, Syntax syntax, String... options) {
        return varIntEncode(buildFieldValue(tag, type, cardinality, syntax, options));
    }

    public static int buildFieldValue(int tag, Type type, Cardinality cardinality) {
        return buildFieldValue(tag, type, cardinality, Syntax.PROTO_3);
    }

    public static int buildFieldValue(int tag, Type type, Cardinality cardinality, Syntax syntax) {
        return buildFieldValue(tag, type, cardinality, syntax, null);
    }

    public static int buildFieldValue(int tag, Type type, Cardinality cardinality, Syntax syntax, String... options) {
        WireType wireType;
        switch (type) {
            case INT32:
            case INT64:
            case UINT32:
            case UINT64:
            case SINT32:
            case SINT64:
            case BOOL:
            case ENUM:
                if (syntax == Syntax.PROTO_3 && cardinality == Cardinality.REPEATED) {
                    wireType = WireType.LENGTH_DELIMITED;
                } else {
                    wireType = WireType.VARINT;
                }
                break;
            case FIXED64:
            case SFIXED64:
            case DOUBLE:
                if (syntax == Syntax.PROTO_3 && cardinality == Cardinality.REPEATED) {
                    wireType = WireType.LENGTH_DELIMITED;
                } else {
                    wireType = WireType.FIXED64;
                }
                break;
            case FIXED32:
            case SFIXED32:
            case FLOAT:
                if (syntax == Syntax.PROTO_3 && cardinality == Cardinality.REPEATED) {
                    wireType = WireType.LENGTH_DELIMITED;
                } else {
                    wireType = WireType.FIXED32;
                }
                break;
            case GROUP:
                wireType = WireType.START_GROUP;
                break;
            default:
                wireType = WireType.LENGTH_DELIMITED;
        }
        return WireFormat.makeTag(tag, wireType);
    }

    public static byte[] varIntEncode(int value) {
        int size = computeRawVarint32Size(value);
        byte[] bs = new byte[size];
        int start = 0;
        if ((value & ~0x7F) == 0) {
            bs[start++] = (byte) value;
        } else {
            bs[start++] = (byte) ((value & 0x7F) | 0x80);
            value >>>= 7;
            if ((value & ~0x7F) == 0) {
                bs[start++] = (byte) value;
            } else {
                bs[start++] = (byte) ((value & 0x7F) | 0x80);
                value >>>= 7;
                if ((value & ~0x7F) == 0) {
                    bs[start++] = (byte) value;
                } else {
                    bs[start++] = (byte) ((value & 0x7F) | 0x80);
                    value >>>= 7;
                    if ((value & ~0x7F) == 0) {
                        bs[start++] = (byte) value;
                    } else {
                        bs[start++] = (byte) ((value & 0x7F) | 0x80);
                        value >>>= 7;
                        if ((value & ~0x7F) == 0) {
                            bs[start++] = (byte) value;
                        }
                    }
                }
            }
        }
        return bs;
    }

    private static void generateProtoField(List<ProtoFieldInfo> profields) {
        List<String> fieldNames = new ArrayList<>();
        profields.forEach(e -> fieldNames.add(e.field.getName()));
        for (int i=0;i<profields.size();i++) {
            ProtoFieldInfo pfi = profields.get(i);
            int tag = i + 1;
            ProtoField pf = buildProtoFieldAnnotation(tag, pfi.field);
            pfi.protoField = pf;
        }
    }

    private static ProtoField buildProtoFieldAnnotation(final int tag, final PbField field) {
        Cardinality cardinality = Cardinality.OPTIONAL;
        if (isList(field.getGenericType())
                || isSet(field.getGenericType())
                || AsmUtil.isArray(field.getGenericType())) {
            cardinality = Cardinality.REPEATED;
        }
        Type type = javaToProtoType(field.getGenericType());
        String[] opitons = null;
        return buildProtoFieldAnnotation(tag, cardinality, type, opitons);
    }

    public static Type javaToProtoType(Class javaType) {
        Type type = Type.BYTES;
        if (javaType.isEnum()) {
            return Type.ENUM;
        }
        String typeName = javaType.getName();
        switch (typeName) {
            case "boolean":
            case "java.lang.Boolean":
                type = Type.BOOL;
                break;
            case "byte":
            case "java.lang.Byte":
            case "char":
            case "java.lang.Character":
            case "short":
            case "java.lang.Short":
            case "int":
            case "java.lang.Integer":
                type = Type.INT32;
                break;
            case "long":
            case "java.lang.Long":
            case "java.util.Date":
            case "java.util.Calendar":
            case "java.time.LocalDateTime":
                type = Type.INT64;
                break;
            case "float":
            case "java.lang.Float":
                type = Type.FLOAT;
                break;
            case "double":
            case "java.lang.Double":
                type = Type.DOUBLE;
                break;
            case "java.lang.String":
                type = Type.STRING;
                break;
        }
        return type;
    }

    public static Type javaToProtoType(java.lang.reflect.Type javaType) {
        Type type = Type.BYTES;
        if (AsmUtil.isMap(javaType)) {
            return Type.MAP;
        }
        if (isPojo(javaType)) {
            return Type.MESSAGE;
        }
        if (AsmUtil.isArray(javaType)) {
            return javaToProtoType(((Class)javaType).getComponentType());
        }
        if (isList(javaType) || isSet(javaType)) {
            if (javaType instanceof ParameterizedType) {
                ParameterizedType pType = (ParameterizedType)javaType;
                java.lang.reflect.Type[] types = pType.getActualTypeArguments();
                if (types != null && types.length > 0) {
                    return javaToProtoType((Class)types[0]);
                }
            }
        }
        if (javaType instanceof ParameterizedType) {
            return type;
        }
        return javaToProtoType((Class)javaType);
    }

    private static ProtoField buildProtoFieldAnnotation(final int tag,
                                                        final Cardinality cardinality, final Type type,
                                                        final String[] options) {
        ProtoField pf = new ProtoField() {
            @Override
            public Cardinality cardinality() {
                return cardinality;
            }

            @Override
            public int tag() {
                return tag;
            }

            @Override
            public Type type() {
                return type;
            }

            @Override
            public String[] options() {
                return options;
            }

            @Override
            public String comment() {
                return "";
            }

            @Override
            public Class<? extends Annotation> annotationType() {
                return null;
            }
        };
        return pf;
    }

    public static void moveForwardBytes(byte[] bs, int pos, int len, int movePos) {
        if (len < 5) {
            int p = pos - movePos;
            for (int i=0;i<len;i++) {
                bs[p++] = bs[pos++];
            }
        } else {
            System.arraycopy(bs, pos, bs, pos - movePos, len);
        }
    }

    private static boolean needEncode(Field field) {
        int mod = field.getModifiers();
        return !Modifier.isStatic(mod) && !Modifier.isTransient(mod);
    }

    private static ProtoField getProtoAnnotation(Field field, Method method) {
        Annotation[] anns = field.getAnnotations();
        ProtoField pf = getProtoAnnotation(anns);
        if (pf != null) {
            return pf;
        }
        if (method != null) {
            anns = method.getAnnotations();
            pf = getProtoAnnotation(anns);
        }
        return pf;
    }

    private static ProtoField getProtoAnnotation(Annotation[] anns) {
        for (Annotation ann : anns) {
            if (ann instanceof ProtoField) {
                return (ProtoField) ann;
            }
        }
        return null;
    }

    /**
     * 查找是否有获取private标识field的方法
     * @param f
     * @param aMethod
     * @return
     */
    private static Method getSetMethod(Field f, Map<String, Method> aMethod) {
        String methodName = "set" + upperCaseFirst(f.getName());
        Method m = aMethod.get(methodName);
        if (m != null && m.getParameters().length == 1) {
            String fd = getDescriptor(f.getGenericType());
            String md = getDescriptor(m.getGenericParameterTypes()[0]);
            if (fd.equals(md)) {
                return m;
            }
        }
        methodName = f.getName();
        m = aMethod.get(methodName);
        if (m != null && m.getParameters().length == 1) {
            String fd = getDescriptor(f.getGenericType());
            String md = getDescriptor(m.getGenericParameterTypes()[0]);
            if (fd.equals(md)) {
                return m;
            }
        }
        return null;
    }

    /**
     * 查找是否有获取private标识field的方法
     * @param f
     * @param aMethod
     * @return
     */
    private static Method getAccessMethod(Field f, Map<String, Method> aMethod) {
        String methodName = "get" + upperCaseFirst(f.getName());
        Method m = aMethod.get(methodName);
        if (m != null && m.getParameters().length == 0) {
            String fd = getDescriptor(f.getGenericType());
            String md = getDescriptor(m.getGenericReturnType());
            if (fd.equals(md)) {
                return m;
            }
        }
        if (f.getType().getName().equals("java.lang.Boolean")
                || f.getType().getName().equals("boolean")) {
            methodName = "is" + upperCaseFirst(f.getName());
        }
        m = aMethod.get(methodName);
        if (m != null && m.getParameters().length == 0) {
            String fd = getDescriptor(f.getGenericType());
            String md = getDescriptor(m.getGenericReturnType());
            if (fd.equals(md)) {
                return m;
            }
        }
        methodName = f.getName();
        m = aMethod.get(methodName);
        if (m != null && m.getParameters().length == 0) {
            String fd = getDescriptor(f.getGenericType());
            String md = getDescriptor(m.getGenericReturnType());
            if (fd.equals(md)) {
                return m;
            }
        }
        return null;
    }

    public static void visitMethod(MethodVisitor mv, int type, String clsName,
                                   String methodName, String desc, boolean isType) {
        mv.visitMethodInsn(type, clsName, methodName, desc, isType);
    }

    public static List<java.lang.reflect.Type> getAllPojoTypes(
            java.lang.reflect.Type genericType) {
        List<java.lang.reflect.Type> types = new ArrayList<>();
        if (genericType instanceof ParameterizedType) {
            ParameterizedType ptype = (ParameterizedType)genericType;
            java.lang.reflect.Type[] ts = ptype.getActualTypeArguments();
            if (ts == null || ts.length == 0) {
                return types;
            }
            for (java.lang.reflect.Type t : ts) {
                if (t instanceof ParameterizedType) {
                    types.addAll(getAllPojoTypes(t));
                } else {
                    if (isPojo(t) && !types.contains(t)) {
                        types.add(t);
                    }
                }
            }
        } else {
            return types;
        }
        return types;
    }

    /**
     *
     * @param value
     * @return
     */
    public static int computeRawVarint32Size(final int value){
        if ((value & (0xffffffff << 7)) == 0)
            return 1;
        if ((value & (0xffffffff << 14)) == 0)
            return 2;
        if ((value & (0xffffffff << 21)) == 0)
            return 3;
        if ((value & (0xffffffff << 28)) == 0)
            return 4;
        return 5;
    }

    /**
     * Compute the number of bytes that would be needed to encode a varint.
     * @param value
     * @return
     */
    public static int computeRawVarint64Size(final long value) {
        if ((value & (0xffffffffffffffffL << 7)) == 0)
            return 1;
        if ((value & (0xffffffffffffffffL << 14)) == 0)
            return 2;
        if ((value & (0xffffffffffffffffL << 21)) == 0)
            return 3;
        if ((value & (0xffffffffffffffffL << 28)) == 0)
            return 4;
        if ((value & (0xffffffffffffffffL << 35)) == 0)
            return 5;
        if ((value & (0xffffffffffffffffL << 42)) == 0)
            return 6;
        if ((value & (0xffffffffffffffffL << 49)) == 0)
            return 7;
        if ((value & (0xffffffffffffffffL << 56)) == 0)
            return 8;
        if ((value & (0xffffffffffffffffL << 63)) == 0)
            return 9;
        return 10;
    }
}
