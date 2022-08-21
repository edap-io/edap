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

import java.util.Collection;
import java.util.Map;

/**
 * 集合类常用工具
 */
public class CollectionUtils {
    private CollectionUtils() {}

    /**
     * 判断列表类对象是否为null以及empty
     * @param collection
     * @return
     */
    public static boolean isEmpty(Collection<?> collection) {
        return collection == null || collection.isEmpty();
    }

    public static boolean isEmpty(Iterable<?> collection) {
        return collection == null || !collection.iterator().hasNext();
    }

    public static boolean isEmpty(Object[] collection) {
        return collection == null || collection.length==0;
    }

    public static boolean isEmpty(Map collection) {
        return collection == null || collection.isEmpty();
    }
}