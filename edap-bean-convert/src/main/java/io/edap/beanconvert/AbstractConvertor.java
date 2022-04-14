/*
 * Copyright 2022 The edap Project
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

package io.edap.beanconvert;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * 框架生成Bean转换器的基类,方便做统一的扩展
 * @param <O>
 * @param <D>
 */
public abstract class AbstractConvertor<O, D> implements Convertor<O, D> {

    public static class ConvertFieldInfo {
        public Field field;
        /**
         * Field的声明的顺序
         */
        public int seq;
        /**
         * 是否有Get方法或者Field是public，可以直接获取Field的值
         */
        public boolean hasGetAccessed;
        /**
         * 是否有Set方法或者Field是public，可以直接给Field赋值
         */
        public boolean hasSetAccessed;
        public Method getMethod;
        public Method setMethod;
    }

    /**
     * 目标Bean的Class实例
     */
    private Class destClazz;
    /**
     * 原始Bean的class实例
     */
    private Class orignalClazz;


    public AbstractConvertor() {
    }

    /**
     * 目标Bean的Class实例
     */
    public Class getDestClazz() {
        return destClazz;
    }

    public void setDestClazz(Class destClazz) {
        this.destClazz = destClazz;
    }

    /**
     * 原始Bean的class实例
     */
    public Class getOrignalClazz() {
        return orignalClazz;
    }

    public void setOrignalClazz(Class orignalClazz) {
        this.orignalClazz = orignalClazz;
    }
}
