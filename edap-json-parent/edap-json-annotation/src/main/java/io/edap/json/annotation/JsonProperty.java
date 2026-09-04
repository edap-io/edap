package io.edap.json.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target({ElementType.ANNOTATION_TYPE, ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
public @interface JsonProperty {
    /**
     * 属性名的默认值
     */
    public static final String USE_DEFAULT_NAME = "";

    /**
     * 指定序列化或者反序列化时key的名称，
     * @return
     */
    String value() default USE_DEFAULT_NAME;
}
