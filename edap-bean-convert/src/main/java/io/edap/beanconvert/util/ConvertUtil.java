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

package io.edap.beanconvert.util;

import io.edap.beanconvert.AbstractConvertor.ConvertFieldInfo;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static io.edap.util.ClazzUtil.*;

/**
 * Bean转换的工具类
 */
public class ConvertUtil {

    public static String CONVERT_LIST_METHOD = "convertList";

    /**
     * 根据Class获取需要转换的类属性信息
     * @param beanCls 需要获取信息测Class对象
     * @return
     */
    public static Map<String, ConvertFieldInfo> getConvertFields(Class beanCls) {
        Map<String, ConvertFieldInfo> profields = new HashMap<>();
        List<Field> fields = getClassFields(beanCls);
        List<Method> methods = getClassMethods(beanCls);
        Map<String, Method> aMethod = new HashMap<>();
        for (Method m : methods) {
            StringBuilder sb = new StringBuilder();
            if (m.getParameters() != null && m.getParameters().length == 1) {
                sb.append("(");
                sb.append(m.getGenericParameterTypes()[0].getTypeName());
                sb.append(")");
            }
            aMethod.put(m.getName() + sb, m);
        }
        int count = fields.size();
        for (int i=0;i<count;i++) {
            Field f = fields.get(i);
            if (needEncode(f)) {
                ConvertFieldInfo pfi = new ConvertFieldInfo();
                pfi.field = f;
                pfi.seq = i;
                Method em = getAccessMethod(f, aMethod);
                pfi.getMethod = em;
                Method setMethod = getSetMethod(f, aMethod);
                if (setMethod != null) {
                    pfi.setMethod = setMethod;
                }
                pfi.hasGetAccessed = Modifier.isPublic(f.getModifiers()) || pfi.getMethod != null;
                pfi.hasSetAccessed = Modifier.isPublic(f.getModifiers()) || pfi.setMethod != null;

                profields.put(f.getName(), pfi);
            }
        }

        return profields;
    }

    /**
     * 查找是否有获取private标识field的方法
     * @param f
     * @param aMethod
     * @return
     */
    private static Method getSetMethod(Field f, Map<String, Method> aMethod) {
        String methodName = "set" + upperCaseFirst(f.getName())  + "(" + f.getGenericType().getTypeName() + ")";
        Method m = aMethod.get(methodName);
        if (m != null && m.getParameters().length == 1) {
            String fd = getDescriptor(f.getGenericType());
            String md = getDescriptor(m.getGenericParameterTypes()[0]);
            if (fd.equals(md)) {
                return m;
            }
        }
        if (f.getType().getName().equals("java.lang.Boolean")
                || f.getType().getName().equals("boolean")) {
            methodName = "set" + upperCaseFirst(f.getName().substring(2))
                    + "(" + f.getGenericType().getTypeName() + ")";
        }
        m = aMethod.get(methodName);
        if (m != null && m.getParameters().length == 1) {
            String fd = getDescriptor(f.getGenericType());
            String md = getDescriptor(m.getGenericParameterTypes()[0]);
            if (fd.equals(md)) {
                return m;
            }
        }
        methodName = f.getName();
        m = aMethod.get(methodName + "(" + f.getGenericType().getTypeName() + ")");
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

    private static boolean needEncode(Field field) {
        int mod = field.getModifiers();
        return !Modifier.isStatic(mod) && !Modifier.isTransient(mod);
    }

    public static boolean booleanValue(Boolean b) {
        return b==null?false:b.booleanValue();
    }

    public static byte byteValue(Byte b) {
        return b==null?0:b.byteValue();
    }

    public static char charValue(Character c) {
        return c==null?0:c.charValue();
    }

    public static short shortValue(Short s) {
        return s==null?0:s.shortValue();
    }

    public static int intValue(Integer i) {
        return i==null?0:i.intValue();
    }

    public static long longValue(Long l) {
        return l==null?0:l.longValue();
    }

    public static float floatValue(Float f) {
        return f==null?0:f.floatValue();
    }

    public static double doubleValue(Double f) {
        return f==null?0:f.doubleValue();
    }
}
