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

package io.edap.util;

import java.lang.reflect.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class ClazzUtil {

    private ClazzUtil() {}

    public static String getDescriptor(Type type) {
        StringBuilder sb = new StringBuilder();
        if (type instanceof ParameterizedType) {
            ParameterizedType ptype = (ParameterizedType)type;
            if ("java.lang.Class".equals(ptype.getRawType().getTypeName())) {
                sb.append("Ljava/lang/Class;");
                return sb.toString();
            }
            sb.append(getDescriptor((Class)ptype.getRawType()));
            Type[] args = ptype.getActualTypeArguments();
            if (args != null) {
                StringBuilder v = new StringBuilder("<");
                for (Type t : args) {
                    v.append(getDescriptor(t));
                }
                v.append(">");
                sb.insert(sb.length() - 1, v);
            }
        } else if (type instanceof TypeVariable) {
            TypeVariable tv = (TypeVariable)type;
            if (tv.getBounds() != null && tv.getBounds().length > 0) {
                sb.append(getDescriptor(tv.getBounds()[0]));
            } else {
                sb.append(getDescriptor(Object.class));
            }
        } else if (type instanceof GenericArrayType) {
            GenericArrayType gat = (GenericArrayType)type;
            sb.append("[").append(getDescriptor(gat.getGenericComponentType()));
        } else if (type instanceof WildcardType) {
            sb.append("?");
        } else {
            sb.append(getDescriptor((Class)type));
        }
        return sb.toString();
    }

    /**
     * 获取Class的JVM内部类型名称
     * @param c Class对象
     * @return
     */
    private static String getDescriptor(final Class<?> c) {
        StringBuilder buf = new StringBuilder();
        Class<?> d = c;
        while (true) {
            if (d.isPrimitive()) {
                char car;
                if (d == Integer.TYPE) {
                    car = 'I';
                } else if (d == Void.TYPE) {
                    car = 'V';
                } else if (d == Boolean.TYPE) {
                    car = 'Z';
                } else if (d == Byte.TYPE) {
                    car = 'B';
                } else if (d == Character.TYPE) {
                    car = 'C';
                } else if (d == Short.TYPE) {
                    car = 'S';
                } else if (d == Double.TYPE) {
                    car = 'D';
                } else if (d == Float.TYPE) {
                    car = 'F';
                } else /* if (d == Long.TYPE) */ {
                    car = 'J';
                }
                buf.append(car);
                return buf.toString();
            } else if (d.isArray()) {
                buf.append('[');
                d = d.getComponentType();
            } else {
                buf.append('L');
                String name = d.getName();
                int len = name.length();
                for (int i = 0; i < len; ++i) {
                    char car = name.charAt(i);
                    buf.append(car == '.' ? '/' : car);
                }
                buf.append(';');
                return buf.toString();
            }
        }
    }

    public static List<Field> getClassFields(Class msgCls) {
        List<Field> fields = new ArrayList<>();
        Field[] af = msgCls.getDeclaredFields();
        for (Field f : af) {
            if (!fields.contains(f)) {
                fields.add(f);
            }
        }
        fillParentField(msgCls, fields);
        return fields;
    }

    public static Field getDeclaredField(Class clazz, String name) throws NoSuchFieldException {
        Field[] af = clazz.getDeclaredFields();
        for (Field f : af) {
            if (name.equals(f.getName())) {
                return f;
            }
        }
        Class pClass = clazz.getSuperclass();
        while (pClass != null && pClass != Object.class) {
            af = pClass.getDeclaredFields();
            for (Field f : af) {
                if (name.equals(f.getName())) {
                    return f;
                }
            }
            pClass = pClass.getSuperclass();
        }
        throw new NoSuchFieldException(name);
    }

    public static void fillParentField(Class msgCls, List<Field> fields) {
        Class pClass = msgCls.getSuperclass();
        while (pClass != null && pClass != Object.class) {
            Field[] af = pClass.getDeclaredFields();
            for (Field f : af) {
                if (!fields.contains(f)) {
                    fields.add(f);
                }
            }
            pClass = pClass.getSuperclass();
        }
    }

    public static List<Method> getClassMethods(Class msgCls) {
        List<Method> methods = new ArrayList<>();
        Method[] am = msgCls.getDeclaredMethods();
        if (am.length > 0) {
            for (Method m : am) {
                if (!methods.contains(m)) {
                    methods.add(m);
                }
            }
        }
        fillParentMethod(msgCls, methods);
        return methods;
    }

    public static void fillParentMethod(Class msgCls, List<Method> aMethod) {
        Class pClass = msgCls.getSuperclass();
        while (pClass != null && pClass != Object.class) {
            Method[] am = pClass.getDeclaredMethods();
            for (Method m : am) {
                if (!aMethod.contains(m)) {
                    aMethod.add(m);
                }
            }
            pClass = pClass.getSuperclass();
        }
    }

    public static String upperCaseFirst(String name) {
        if (StringUtil.isEmpty(name)) {
            return "";
        }
        if (name.length() == 1) {
            return name.toUpperCase(Locale.ENGLISH);
        } else {
            return name.substring(0, 1).toUpperCase(Locale.ENGLISH)
                    + name.substring(1);
        }
    }

    public static String getTypeName(Type type) {
        if (type instanceof ParameterizedType) {
            ParameterizedType ptype = (ParameterizedType)type;
            return ((Class)ptype.getRawType()).getName();
        } else {
            Class cls = (Class)type;
            if (cls.isPrimitive()) {
                return getDescriptor(cls);
            } else {
                return cls.getName();
            }
        }
    }
}
