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

package io.edap.toml;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static io.edap.toml.Parser.parseDottedKey;
import static java.util.Objects.requireNonNull;

public interface TomlMap {
    /**
     * 改toml表的大小
     * @return 该map的大小
     */
    int size();

    /**
     * 改toml中是否没有条目
     * @return 是否没有条目
     */
    boolean isEmpty();

    /**
     * 检查该toml的map中带"."的key是否被设置
     * @param dottedKey 路径用"."分割
     * @return 是否被设置
     */
    default boolean contains(String dottedKey) {
        return contains(parseDottedKey(dottedKey));
    }

    /**
     * 检查路径在toml中是否被设置
     * @param path 用列表来表示的路径信息
     * @return 是否被设置
     */
    boolean contains(List<String> path);

    /**
     * 返回toml的map的Key的集合
     * @return
     */
    Set<String> keySet();

    /**
     * 返回toml中键值对的集合
     * @return 键值对的集合
     */
    Set<Map.Entry<String, Object>> entrySet();

    /**
     * 根据给定的用"."分割的key从toml文档获取值
     * @param dottedKey 用"."分割的路径(Key) (比如： {@code "server.address.port"}).
     * @return 返回改路径设置的值
     */
    default Object get(String dottedKey) {
        return parseDottedKey(dottedKey);
    }

    /**
     * 根据给定的用列表表示的Key从toml文档中获取值
     * @param path 用列表的方式表示的路径
     * @return 返回改路径设置的值
     */
    Object get(List<String> path);

    default String getString(String dottedKey) {
        requireNonNull(dottedKey);
        return getString(parseDottedKey(dottedKey));
    }

    default String getString(List<String> path) {
        Object value = get(path);
        if (value == null) {
            return null;
        }
        if (!(value instanceof String)) {
            throw new TomlInvalidTypeException(
                    "Value of '" + Toml.joinKeyPath(path) + "' is a " + TomlType.typeNameFor(value));
        }
        return (String) value;
    }
}
