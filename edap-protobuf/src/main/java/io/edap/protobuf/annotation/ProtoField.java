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

package io.edap.protobuf.annotation;

import io.edap.protobuf.wire.Field.Cardinality;
import io.edap.protobuf.wire.Field.Type;

import java.lang.annotation.*;

/**
 * ProtoField的注释，增加该注释则说明类的属性作为protocol buffer中Message的一个属性
 */

@Target({ ElementType.FIELD })
@Retention(RetentionPolicy.RUNTIME)
@Inherited
@Documented
public @interface ProtoField {
    /**
     * proto属性的基数
     * @return
     */
    Cardinality cardinality() default Cardinality.OPTIONAL;
    /**
     * 属性的顺序编号从1开始的正数
     * @return
     */
    int tag();
    /**
     * 属性对应的protobuf的数据类型
     * @return
     */
    Type type();
    /**
     * 消息属性的Option选项列表用","分隔
     * @return
     */
    String[] options() default {};
    /**
     * 生成proto文件是属性上的注释信息
     * @return
     */
    String comment() default "";
}