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

package io.edap.data.jdbc.annotation;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

/**
 * 一个字段是否创建数据库的索引注解
 * @author louis
 */
@Target({METHOD, FIELD}) @Retention(RUNTIME)
public @interface Index {
    /**
     * 定义索引的名称
     * @return 返回索引名称
     */
    String name() default "";
    
    /**
     * 索引类型
     * @return 返回索引类型的字符串 
     */
    String type() default "";
    
    /**
     * 该属性是否有唯一索引不允许有相同的值
     * @return 如果为true则说明该属性在数据库中不允许有重复的值
     */
    boolean unique() default false;
}
