/*
 * Copyright 2023 The edap Project
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

package io.edap.json.model;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

public class JsonFieldInfo {

    /**
     * 需要序列化的属性的数据，如果属性为private则需要有相应的get方法，如果为public则
     * 可以没有get的方法来进行访问，也需要序列化
     */
    public Field field;
    /**
     * 如果属性为private，如果该属性有相应get方法则和属性对应的Method的数据
     */
    public Method method;
    /**
     * 如果属性为private，如果该属性有相应set方法则和属性对应的Method的数据
     */
    public Method setMethod;

    public String jsonFieldName;

    public int jsonFieldHash;

    /**
     * 是否有Get方法或者Field是public，可以直接获取Field的值
     */
    public boolean hasGetAccessed;
    /**
     * 是否有Set方法或者Field是public，可以直接给Field赋值
     */
    public boolean hasSetAccessed;
    /**
     *
     */
    public String type;
    /**
     * 属性是否是Map
     */
    public boolean isMap;


    public JsonFieldInfo() {
    }
}
