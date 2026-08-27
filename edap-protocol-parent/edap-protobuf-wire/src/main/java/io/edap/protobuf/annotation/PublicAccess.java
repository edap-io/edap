package io.edap.protobuf.annotation;

import java.lang.annotation.*;

/**
 * 方法标记该方法为所有人都可以公开访问
 */
@Target({ ElementType.METHOD })
@Retention(RetentionPolicy.RUNTIME)
@Inherited
@Documented
public @interface PublicAccess {
}
