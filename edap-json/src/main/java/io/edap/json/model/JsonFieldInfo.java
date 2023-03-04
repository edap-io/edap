package io.edap.json.model;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

public class JsonFieldInfo {

    /**
     * 需要序列化的属性的数据，如果属性为private则需要有相应的get方法，如果为public则
     * 可以没有get的方法来进行访问，也需要序列化
     */
    public final Field field;
    /**
     * 如果属性为private，如果该属性有相应get方法则和属性对应的Method的数据
     */
    public final Method method;
    /**
     * 如果属性为private，如果该属性有相应set方法则和属性对应的Method的数据
     */
    public Method setMethod;
    /**
     *
     */
    public String type;
    /**
     * 属性是否是Map
     */
    public boolean isMap;


    public JsonFieldInfo(Field field, Method method) {
        this.field = field;
        this.method = method;
    }
}
