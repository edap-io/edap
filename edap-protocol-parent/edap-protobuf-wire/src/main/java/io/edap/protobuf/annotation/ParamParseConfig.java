package io.edap.protobuf.annotation;

import java.lang.annotation.*;

/**
 * 函数解析配置，主要用户http请求时函数入参的对象怎么使用http的参数组装的问题
 */
@Target({ ElementType.METHOD })
@Retention(RetentionPolicy.RUNTIME)
@Inherited
@Documented
public @interface ParamParseConfig {

    KeyValuePair[] configs();
}
