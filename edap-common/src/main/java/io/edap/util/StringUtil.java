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

package io.edap.util;

/**
 * 字符串常用的操作函数
 */
public class StringUtil {
    private StringUtil() {}

    /**
     * 判断字符串的对象是否为空，如果字符串时空指针或者字符串为空均为true
     * @param str 字符串对象
     * @return 是否为空null指针或者是空字符串则返回true
     */
    public static boolean isEmpty(String str) {
        return str==null || str.isEmpty();
    }
}