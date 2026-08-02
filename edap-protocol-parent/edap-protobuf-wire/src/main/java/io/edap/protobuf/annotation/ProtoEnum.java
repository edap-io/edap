package io.edap.protobuf.annotation;

import java.lang.annotation.*;

@Target({ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Inherited
@Documented
public @interface ProtoEnum {
    /**
     * 生成该java文件的proto文件的名称
     * @return
     */
    String protoFile();
}
