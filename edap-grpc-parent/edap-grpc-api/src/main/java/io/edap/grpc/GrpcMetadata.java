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

package io.edap.grpc;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * gRPC 的元数据（对应 HTTP/2 的 header）。
 *
 * <p>gRPC 规定元数据的 key 必须是<b>小写</b>的 ASCII 字符串，本类在写入时统一转为小写，
 * 因此读取时也不区分大小写。同一个 key 可以有多个值。
 *
 * <p>请求元数据来自 HTTP/2 请求头（伪头 {@code :method}、{@code :path} 等不属于元数据，
 * 由调用方在构造时过滤掉），响应元数据会被写入 HTTP/2 响应头。
 */
public final class GrpcMetadata {

    private final Map<String, List<String>> values;

    public GrpcMetadata() {
        this.values = new LinkedHashMap<>();
    }

    /**
     * 用已有的多值 map 构造元数据。
     *
     * @param source key 到多值列表的映射，为 null 时构造空元数据
     */
    public GrpcMetadata(Map<String, List<String>> source) {
        this();
        if (source == null) {
            return;
        }
        for (Map.Entry<String, List<String>> entry : source.entrySet()) {
            List<String> list = entry.getValue();
            if (list == null) {
                continue;
            }
            for (String value : list) {
                add(entry.getKey(), value);
            }
        }
    }

    /**
     * @return 空的元数据
     */
    public static GrpcMetadata empty() {
        return new GrpcMetadata();
    }

    /**
     * 取 key 对应的第一个值。
     *
     * @param key 元数据的 key，忽略大小写
     * @return 第一个值，不存在时返回 null
     */
    public String get(String key) {
        List<String> list = values.get(normalize(key));
        if (list == null || list.isEmpty()) {
            return null;
        }
        return list.get(0);
    }

    /**
     * 取 key 对应的所有值。
     *
     * @param key 元数据的 key，忽略大小写
     * @return 值列表，不存在时返回空列表
     */
    public List<String> getAll(String key) {
        List<String> list = values.get(normalize(key));
        if (list == null || list.isEmpty()) {
            return Collections.emptyList();
        }
        return Collections.unmodifiableList(list);
    }

    /**
     * 追加一个值，同一个 key 可以有多个值。
     *
     * @param key   元数据的 key
     * @param value 值，为 null 时忽略
     */
    public void add(String key, String value) {
        if (key == null || key.isEmpty() || value == null) {
            return;
        }
        values.computeIfAbsent(normalize(key), k -> new ArrayList<>()).add(value);
    }

    /**
     * 设置 key 对应的唯一值，覆盖已存在的所有值。
     *
     * @param key   元数据的 key
     * @param value 值，为 null 时清空该 key
     */
    public void set(String key, String value) {
        if (key == null || key.isEmpty()) {
            return;
        }
        List<String> list = new ArrayList<>();
        if (value != null) {
            list.add(value);
        }
        values.put(normalize(key), list);
    }

    /**
     * 删除 key 对应的所有值。
     *
     * @param key 元数据的 key
     */
    public void remove(String key) {
        values.remove(normalize(key));
    }

    /**
     * @param key 元数据的 key
     * @return 该 key 是否存在
     */
    public boolean contains(String key) {
        return values.containsKey(normalize(key));
    }

    /**
     * @return 所有的 key，key 均为小写
     */
    public Set<String> keys() {
        return Collections.unmodifiableSet(values.keySet());
    }

    /**
     * @return 元数据中 key 的个数
     */
    public int size() {
        return values.size();
    }

    /**
     * @return 元数据是否为空
     */
    public boolean isEmpty() {
        return values.isEmpty();
    }

    /**
     * @return key 到多值列表的视图，只读
     */
    public Map<String, List<String>> asMap() {
        return Collections.unmodifiableMap(values);
    }

    @Override
    public String toString() {
        return values.toString();
    }

    private static String normalize(String key) {
        if (key == null) {
            return "";
        }
        return key.toLowerCase(Locale.ENGLISH);
    }
}
