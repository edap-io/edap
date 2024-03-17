package io.edap.eproto.util;

import static io.edap.util.AsmUtil.isMap;

public class EprotoUtil {

    private EprotoUtil() {}

    /**
     * 判断一个Class的是否是Map的子类，如果是返回Map的类型信息，否则返回null
     * @param clazz
     * @return
     */
    public static java.lang.reflect.Type parentMapType(Class clazz) {
        if (isMap(clazz)) {
            return clazz;
        }
        Class pclazz = clazz.getSuperclass();
        Class cclazz = clazz;
        while (pclazz != null) {
            if (isMap(pclazz)) {
                return cclazz.getGenericSuperclass();
            }
            cclazz = pclazz;
            pclazz = pclazz.getSuperclass();

        }
        return null;
    }
}
