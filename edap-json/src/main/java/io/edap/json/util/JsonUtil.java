/*
 * Copyright 2023 The edap Project
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

package io.edap.json.util;

import io.edap.json.enums.DataType;
import io.edap.json.enums.JsonVersion;
import io.edap.json.model.JsonFieldInfo;
import io.edap.util.StringUtil;

import java.lang.annotation.Annotation;
import java.lang.reflect.*;
import java.math.BigDecimal;
import java.util.*;

import static io.edap.json.consts.JsonConsts.END_OF_NUMBER;
import static io.edap.json.consts.JsonConsts.INVALID_CHAR_FOR_NUMBER;
import static io.edap.util.AsmUtil.isMap;
import static io.edap.util.AsmUtil.isPojo;
import static io.edap.util.ClazzUtil.*;

public class JsonUtil {

    private JsonUtil() {}

    public static boolean[] ECMAS_ALLOW_FIRST_CHARS = new boolean[128];
    public static boolean[] ECMAS_ALLOW_OTHER_CHARS = new boolean[128];

    public static final long POW10[] = {
            1, 10, 100, 1000, 10000, 100000, 1000000, 10000000, 100000000,
            1000000000, 10000000000L, 100000000000L, 1000000000000L,
            10000000000000L, 100000000000000L, 1000000000000000L};

    public static int[] INT_DIGITS = new int[128];

    public static int[] HEX_DIGITS = new int[128];
    static {
        // 标识符首字母允许的符号
        for (int i=0;i<128;i++) {
            ECMAS_ALLOW_FIRST_CHARS[i] = false;
            INT_DIGITS[i] = INVALID_CHAR_FOR_NUMBER;
            HEX_DIGITS[i] = INVALID_CHAR_FOR_NUMBER;
        }

//        INT_DIGITS[',']  = END_OF_NUMBER;
//        INT_DIGITS[']']  = END_OF_NUMBER;
//        INT_DIGITS['}']  = END_OF_NUMBER;
//        INT_DIGITS[' ']  = END_OF_NUMBER;
//        INT_DIGITS['\t'] = END_OF_NUMBER;
//        INT_DIGITS['\r'] = END_OF_NUMBER;
//        INT_DIGITS['\n'] = END_OF_NUMBER;

        ECMAS_ALLOW_FIRST_CHARS['$'] = true; // $符号
        ECMAS_ALLOW_FIRST_CHARS['_'] = true; // _下划线符号
        for (int i='A';i<='Z';i++) {
            ECMAS_ALLOW_FIRST_CHARS[i] = true; // 大写字母
            HEX_DIGITS[i] = i - 55;
        }
        for (int i='a';i<='z';i++) {
            ECMAS_ALLOW_FIRST_CHARS[i] = true; // 小写字母
            HEX_DIGITS[i] = i - 87;
        }

        // 标识符其他位置允许的符号
        for (int i=0;i<128;i++) {
            ECMAS_ALLOW_OTHER_CHARS[i] = false;
        }
        ECMAS_ALLOW_OTHER_CHARS['$'] = true; // $符号
        ECMAS_ALLOW_OTHER_CHARS['_'] = true; // _下划线符号
        for (int i='A';i<='Z';i++) {
            ECMAS_ALLOW_OTHER_CHARS[i] = true; // 大写字母
        }
        for (int i='a';i<='z';i++) {
            ECMAS_ALLOW_OTHER_CHARS[i] = true; // 小写字母
        }
        for (int i='0';i<='9';i++) {
            ECMAS_ALLOW_OTHER_CHARS[i] = true; // 小写字母
            INT_DIGITS[i] = i - '0';
            HEX_DIGITS[i] = i - '0';
        }
    }

    public static boolean isIdentifierNameFirst(char c) {
        if (c > 128) {
            return false;
        }
        return ECMAS_ALLOW_FIRST_CHARS[c];
    }

    public static boolean isNumber(byte b) {
        if (b >= '0' && b <= '9') {
            return true;
        }
        return false;
    }

    public static boolean isNumberEnd(byte b) {
        return isNumberEnd((char)b);
    }

    public static boolean isNumberEnd(char b) {
        switch (b) {
            case ',':
            case ']':
            case '}':
            case ' ':
            case '\t':
            case '\r':
            case '\n':
                return true;
            default:
        }
        return false;
    }

    public static boolean isIdentifierNameOther(char c) {
        if (c >= 128) {
            return false;
        }
        return ECMAS_ALLOW_OTHER_CHARS[c];
    }

    public static boolean isIdentifierNameOther(byte c) {
        if (c >= 128) {
            return false;
        }
        return ECMAS_ALLOW_OTHER_CHARS[c];
    }

    public static String buildDecoderName(Class pojoCls, DataType dataType, JsonVersion jsonVersion) {
        if (pojoCls == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder("ejd");
        if (dataType == DataType.STRING) {
            sb.append('s');
        } else {
            sb.append('b');
        }
        if (jsonVersion == JsonVersion.JSON5) {
            sb.append('5');
        }
        sb.append('.');
        if (pojoCls.getPackage() != null) {
            sb.append(pojoCls.getPackage().getName()).append(".");
        }
        sb.append(pojoCls.getSimpleName()).append("Decoder");
        return sb.toString();
    }

    public static String buildEncoderName(Class pojoCls) {
        if (pojoCls == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder("eje.");
        if (pojoCls.getPackage() != null) {
            sb.append(pojoCls.getPackage().getName()).append(".");
        }
        sb.append(pojoCls.getSimpleName()).append("Encoder");
        return sb.toString();
    }

    public static String buildEncoderName(Class pojoCls, DataType dataType) {
        if (pojoCls == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder("eje");
        if (dataType == DataType.STRING) {
            sb.append('s');
        } else {
            sb.append('b');
        }
        sb.append('.');
        if (pojoCls.getPackage() != null) {
            sb.append(pojoCls.getPackage().getName()).append(".");
        }
        sb.append(pojoCls.getSimpleName()).append("Encoder");
        return sb.toString();
    }

    public static boolean isBaseType(Field field) {
        return field.getType().isPrimitive();
    }

    public static boolean isBaseType(Class type) {
        return type.isPrimitive();
    }

    public static boolean isBaseObjectType(Class type) {
        switch (type.getName()) {
            case "java.lang.Integer":
            case "java.lang.Boolean":
            case "java.lang.Long":
            case "java.lang.Byte":
            case "java.lang.Short":
            case "java.lang.Float":
            case "java.lang.Double":
            case "java.lang.Character":
                return true;
            default:
                return false;
        }
    }

    public static String getReadMethod(JsonFieldInfo jfi) {
        String type = jfi.field.getType().getName();
        String method = "";
        switch (type) {
            case "java.lang.Boolean":
            case "boolean":
                method = "readBoolean";
                break;
            case "java.lang.String":
                method = "readString";
                break;
            case "java.lang.Integer":
            case "int":
                method = "readInt";
                break;
            case "java.lang.Long":
            case "long":
                method = "readLong";
                break;
            case "java.lang.Double":
            case "double":
                method = "readDouble";
                break;
            case "java.lang.Float":
            case "float":
                method = "readFloat";
                break;
            default:
                method = "readObject";
        }
        return method;
    }

    public static String getWriteMethod(Field field) {
        String type = field.getType().getName();
        String method = "";
        if (isMap(field.getGenericType())) {
            return "write";
        }
        switch (type) {
            case "java.lang.String":
            case "int":
            case "java.lang.Integer":
            case "long":
            case "java.long.Long":
            case "double":
            case "java.lang.Double":
            case "float":
            case "java.lang.Float":
            case "java.math.BigDecimal":
                method = "write";
                break;
            default:
                method = "writeObject";
        }
        return method;
    }

    public static String getWriteMethod(Class fieldType) {
        String type = fieldType.getName();
        String method = "";
        switch (type) {
            case "java.lang.String":
            case "int":
            case "java.lang.Integer":
            case "long":
            case "java.long.Long":
            case "double":
            case "java.lang.Double":
            case "float":
            case "java.lang.Float":
            case "java.math.BigDecimal":
                method = "write";
                break;
            default:
                method = "writeObject";
        }
        return method;
    }

    public static boolean isRepeatedArray(java.lang.reflect.Type type) {
        if (type instanceof Class) {
            Class arrayCls = (Class)type;
            return arrayCls.isArray() && !"[B".equals(arrayCls.getName())
                    && !"[Ljava.lang.Byte;".equals(arrayCls.getName());
        }
        return false;
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

    public static List<JsonFieldInfo> getCodecFieldInfos(Class pojoCls) {
        List<JsonFieldInfo> fs = new ArrayList<>();
        List<Field> fields = getClassFields(pojoCls);

        Iterator itr = fields.iterator();
        List<Field> needCodecFields = new ArrayList<>();
        while (itr.hasNext()) {
            Field f = (Field)itr.next();
            if (Modifier.isStatic(f.getModifiers()) || Modifier.isTransient(f.getModifiers())) {
                continue;
            }
            needCodecFields.add(f);
        }
        Method[] am = pojoCls.getDeclaredMethods();
        List<Method> allMethod = new ArrayList<>();
        for (Method m : am) {
            if (!allMethod.contains(m)) {
                allMethod.add(m);
            }
        }

        fillParentMethod(pojoCls, allMethod);
        Map<String, Method> aMethod = new HashMap<>();
        for (int i=0;i<allMethod.size();i++) {
            Method m = allMethod.get(i);
            aMethod.put(m.getName(), m);
        }

        for (Field f : needCodecFields) {
            JsonFieldInfo jfi = new JsonFieldInfo();
            jfi.field = f;
            Method m = getAccessMethod(f, "get", aMethod);
            if (m != null) {
                jfi.method = m;
            } else {
                m = getAccessMethod(f, "is", aMethod);
                if (m != null) {
                    jfi.method = m;
                }
            }
            Method setMethod = getSetMethod(f, aMethod);
            if (setMethod != null) {
                jfi.setMethod = setMethod;
            }
            fs.add(jfi);
        }



        for (JsonFieldInfo jfi : fs) {
            if (isMap(jfi.field.getGenericType())) {
                jfi.isMap = true;
            }
            jfi.hasGetAccessed = Modifier.isPublic(jfi.field.getModifiers()) || jfi.method != null;
            jfi.hasSetAccessed = Modifier.isPublic(jfi.field.getModifiers()) || jfi.setMethod != null;
        }
        //Collections.sort(fs, Comparator.comparing((JsonFieldInfo o) -> o.field.getName()));
        return fs;
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
            if (f.getGenericType().getTypeName().equals(m.getGenericParameterTypes()[0].getTypeName())) {
                return m;
            }
        }
        methodName = f.getName();
        m = aMethod.get(methodName);
        if (m != null && m.getParameters().length == 1) {
            if (f.getGenericType().getTypeName().equals(m.getGenericParameterTypes()[0].getTypeName())) {
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
    private static Method getAccessMethod(Field f, String prefix, Map<String, Method> aMethod) {
        String methodName = prefix + upperCaseFirst(f.getName());
        Method m = aMethod.get(methodName);
        if (m != null && m.getParameters().length == 0) {
            if (f.getGenericType().getTypeName().equals(m.getGenericReturnType().getTypeName())) {
                return m;
            }
        }
        methodName = f.getName();
        m = aMethod.get(methodName);
        if (m != null && m.getParameters().length == 0) {
            if (f.getGenericType().getTypeName().equals(m.getGenericReturnType().getTypeName())) {
                return m;
            }
        }
        return null;
    }

    public static String getJsonFieldName(Field field, String jsonFieldName) {
        String name = field.getName();
        if (!StringUtil.isEmpty(jsonFieldName)) {
            name = jsonFieldName;
        }
        return name;
    }

    public static int getIntValue(Object obj) {
        if (obj == null) {
            return 0;
        }
        if (obj instanceof Integer) {
            return ((Integer)obj);
        } else if (obj instanceof Long) {
            return ((Long)obj).intValue();
        } else if (obj instanceof Double) {
            return ((Double)obj).intValue();
        } else if (obj instanceof String) {
            return Integer.parseInt((String)obj);
        } else if (obj instanceof BigDecimal) {
            return ((BigDecimal)obj).intValue();
        }
        throw  new NumberFormatException("Not int value");
    }

    public static <Object> long getLongValue(Object obj) {
        if (obj == null) {
            return 0L;
        }
        if (obj instanceof Integer) {
            return ((Integer)obj).longValue();
        } else if (obj instanceof Long) {
            return ((Long)obj);
        } else if (obj instanceof String) {
            return Long.parseLong((String)obj);
        } else if (obj instanceof BigDecimal) {
            return ((BigDecimal)obj).longValue();
        }
        throw  new NumberFormatException("Not long value");
    }

    public static <Object> float getFloatValue(Object obj) {
        if (obj == null) {
            return 0F;
        }
        if (obj instanceof Float) {
            return ((Float)obj);
        } else if (obj instanceof Double) {
            return ((Double)obj).floatValue();
        } else if (obj instanceof Integer) {
            return ((Integer)obj).floatValue();
        } else if (obj instanceof Long) {
            return ((Long)obj).floatValue();
        } else if (obj instanceof String) {
            return Float.parseFloat((String)obj);
        } else if (obj instanceof BigDecimal) {
            return ((BigDecimal)obj).floatValue();
        }
        throw  new NumberFormatException("Not float value");
    }

    public static <Object> Float getFloat(Object obj) {
        if (obj == null) {
            return null;
        }
        return getFloatValue(obj);
    }

    public static <Object> double getDoubleValue(Object obj) {
        if (obj == null) {
            return 0D;
        }
        if (obj instanceof Double) {
            return ((Double)obj);
        } else if (obj instanceof Float) {
            return ((Float)obj).doubleValue();
        } else if (obj instanceof Integer) {
            return ((Integer)obj).doubleValue();
        } else if (obj instanceof Long) {
            return ((Long)obj).doubleValue();
        } else if (obj instanceof String) {
            return Double.parseDouble((String)obj);
        } else if (obj instanceof BigDecimal) {
            return ((BigDecimal)obj).doubleValue();
        }
        throw  new NumberFormatException("Not double value");
    }

    public static <Object> Double getDouble(Object obj) {
        if (obj == null) {
            return null;
        }
        return getDoubleValue(obj);
    }

    public static <Object> boolean getBooleanValue(Object obj) {
        if (obj == null) {
            return false;
        }
        if (obj instanceof Boolean) {
            return (Boolean)obj;
        } else if (obj instanceof Integer) {
            return (((Integer)obj) != 0);
        } else if (obj instanceof Long) {
            return (((Long)obj) != 0);
        } else if (obj instanceof String) {
            String v = (String)obj;
            return "t".equalsIgnoreCase(v) || "true".equalsIgnoreCase(v) || "1".equals(v);
        }
        return false;
    }

    public static <Object> Boolean getBoolean(Object obj) {
        if (obj == null) {
            return null;
        }
        if (obj instanceof Boolean) {
            return (Boolean)obj;
        } else if (obj instanceof Integer) {
            return (((Integer)obj) != 0);
        } else if (obj instanceof Long) {
            return (((Long)obj) != 0);
        } else if (obj instanceof String) {
            String v = (String)obj;
            return "t".equalsIgnoreCase(v) || "true".equalsIgnoreCase(v) || "1".equals(v);
        }
        return false;
    }
}
