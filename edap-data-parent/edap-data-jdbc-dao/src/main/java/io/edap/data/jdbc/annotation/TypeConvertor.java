package io.edap.data.jdbc.annotation;


import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

@Target({METHOD, FIELD})
@Retention(RUNTIME)
public @interface TypeConvertor {
    /**
     * 数据库字段类型
     * @return
     */
    String columnType();

    /**
     * jdbc占位符的字符串，如果有类型转换的函数，则包含函数的部分
     * @return
     */
    String jdbcPlaceholder();
}
