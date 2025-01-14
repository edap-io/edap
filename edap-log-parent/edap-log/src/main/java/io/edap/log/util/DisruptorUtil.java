/*
 * Copyright 2023 The edap Project
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 *  with the License. You may obtain a copy of the License at:
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 *  WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 *  License for the specific language governing permissions and limitations
 *  under the License.
 */

package io.edap.log.util;

import com.lmax.disruptor.SleepingWaitStrategy;
import com.lmax.disruptor.WaitStrategy;
import io.edap.log.LogConfig;
import io.edap.log.config.DisruptorConfig;
import io.edap.log.config.QueueConfig;
import io.edap.util.CollectionUtils;
import io.edap.util.StringUtil;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static io.edap.log.consts.LogConsts.DEFAULT_QUEUE_SIZE;

public class DisruptorUtil {

    private DisruptorUtil(){}

    public static void checkSetConfig(DisruptorConfig config, LogConfig.ArgNode arg) {
        switch (arg.getName()) {
            case "capacity":
                int cap;
                try {
                    cap = Integer.parseInt(arg.getValue());
                    if (cap <= 0) {
                        cap = DEFAULT_QUEUE_SIZE;
                    }
                    config.setCapacity(cap);
                } catch (NumberFormatException nume) {
                    config.setCapacity(DEFAULT_QUEUE_SIZE);
                    throw nume;
                }
                break;
            case "waitStrategy":
                try {
                    String clazzName = "com.lmax.disruptor." + arg.getValue();
                    Class clazz;
                    Map<String, String> attrs = arg.getAttributes();
                    WaitStrategy waitStrategy = null;
                    if (CollectionUtils.isEmpty(attrs)) {
                        waitStrategy = (WaitStrategy) instance(clazzName);
                    } else {
                        String attrClazz = attrs.get("class");
                        List<LogConfig.ArgNode> children = arg.getChilds();
                        if (!StringUtil.isEmpty(attrClazz)) {
                            int dotIndex = attrClazz.indexOf(".");
                            if (dotIndex == -1) {
                                clazzName = "com.lmax.disruptor." + attrClazz;
                            } else {
                                clazzName = attrClazz;
                            }
                            clazz = DisruptorUtil.class.getClassLoader().loadClass(clazzName);
                            Constructor<?>[] consts = clazz.getDeclaredConstructors();
                            if (CollectionUtils.isEmpty(children)) {
                                for (Constructor c : consts) {
                                    if (c.getParameterCount() == 0) {
                                        waitStrategy = (WaitStrategy) c.newInstance();
                                    }
                                }
                                if (waitStrategy == null) {
                                    throw new RuntimeException("clazzName haven't Constructor");
                                }
                            } else {
                                List<ConstructorParam> params = new ArrayList<>();
                                for (LogConfig.ArgNode node : children) {
                                    ConstructorParam p = new ConstructorParam();
                                    try {
                                        if ("arg".equals(node.getName())) {
                                            String type;
                                            if (CollectionUtils.isEmpty(node.getAttributes())) {
                                                type = "";
                                            } else {
                                                type = node.getAttributes().get("type");
                                            }
                                            if (StringUtil.isEmpty(type)) {
                                                Object v = instance(node.getValue());
                                                p.type  = v.getClass();
                                                p.value = v;
                                            } else {
                                                p.type = toJavaType(type);
                                                p.value = toJavaValue(p.type, node.getValue());
                                            }
                                            params.add(p);
                                        }
                                    } catch (Throwable t) {
                                        throw new RuntimeException(t);
                                    }
                                }
                                int count = params.size();
                                for (Constructor c : consts) {
                                    if (c.getParameterCount() == count) {
                                        Object[] objs = new Object[count];
                                        for (int i=0;i<count;i++) {
                                            objs[i] = params.get(i).value;
                                        }
                                        waitStrategy = (WaitStrategy) c.newInstance(objs);
                                    }
                                }
                                if (waitStrategy == null) {
                                    throw new RuntimeException("clazzName haven't Constructor");
                                }
                            }
                        }
                    }
                    config.setWaitStrategy(waitStrategy);
                } catch (Throwable t) {
                    config.setWaitStrategy(new SleepingWaitStrategy());
                    throw new RuntimeException("waitStrategy " + arg.getValue() + " is invalid!", t);
                }
                break;
            default:
                throw new RuntimeException(arg.getName() + " is unsupport!");
        }
    }

    private static Object instance(String clazzName) {
        try {
            Class clazz = DisruptorUtil.class.getClassLoader().loadClass(clazzName);
            Constructor<?>[] cs = clazz.getDeclaredConstructors();
            for (Constructor c : cs) {
                if (c.getParameterCount() == 0) {
                    return c.newInstance();
                }
            }
        } catch (Throwable e) {
            throw new RuntimeException(e);
        }
        throw new RuntimeException(clazzName + " haven't default Constructor!");
    }

    private static Object toJavaValue(Class type, String value) {
        if (type.isEnum()) {
            return evalEnumValue(type, value);
        }
        switch (type.getName()) {
            case "int":
                return Integer.parseInt(value);
            case "java.lang.Integer":
                return Integer.valueOf(value);
            case "float":
                return Float.parseFloat(value);
            case "java.lang.Float":
                return Float.valueOf(value);
            case "double":
                return Double.parseDouble(value);
            case "java.lang.Double":
                return Double.valueOf(value);
            case "boolean":
                return Boolean.parseBoolean(value);
            case "java.lang.Boolean":
                return Boolean.valueOf(value);
            case "char":
                return value.charAt(0);
            case "java.lang.Character":
                return Character.valueOf(value.charAt(0));
            case "byte":
                return Byte.parseByte(value);
            case "java.lang.Byte":
                return Byte.valueOf(value);
            case "long":
                return Long.parseLong(value);
            case "java.lang.Long":
                return Long.valueOf(value);
        }

        return null;
    }

    private static Object evalEnumValue(Class enumClass, String name) {
        Object[] vs = enumClass.getEnumConstants();
        for (Object o : vs) {
            String vname = ((Enum)o).name();
            if (vname.equals(name)) {
                return o;
            }
        }
        throw new RuntimeException("enumClass haven't " + name + " value");
    }

    private static Class toJavaType(String typeStr) throws ClassNotFoundException {
        String lowerCaseName = typeStr.toLowerCase(Locale.ENGLISH);
        switch (lowerCaseName) {
            case "byte":
                return byte.class;
            case "boolean":
                return boolean.class;
            case "char":
                return char.class;
            case "int":
                return int.class;
            case "float":
                return float.class;
            case "short":
                return short.class;
            case "long":
                return long.class;
            case "double":
                return double.class;
            default:
                return DisruptorUtil.class.getClassLoader().loadClass(typeStr);
        }
    }

    public static class ConstructorParam {
        private Class type;
        private Object value;
    }

}
