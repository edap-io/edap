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

package io.edap.json;

import io.edap.util.CollectionUtils;
import io.edap.util.StringUtil;

import java.util.List;
import java.util.Map;

/**
 * 定义将json解析后返回的对象，扩展map接口增加根据路径获取值的接口
 */
public interface JsonMap extends Map<String, Object> {
    /**
     * 根据路径获取该路径对应的值,内部List的遍历方式使用下标的方式，尽量使用ArrayList作为参数
     * @param path 用列表表示的路径信息
     * @return 返回改路径对应的值，不存在则返回null
     */
    default Object getByPath(List<String> path) {
        if (CollectionUtils.isEmpty(path)) {
            return null;
        }
        Object v = get(path.get(0));
        if (path.size() == 1) {
            return v;
        }
        if (!(v instanceof Map)) {
            return null;
        }
        int i = 1;
        for (;i<path.size();i++) {
            if (!(v instanceof Map)) {
                return null;
            }
            v = ((Map<?, ?>)v).get(path.get(i));
        }

        return v;
    }

    /**
     * 根据路径信息获取该路径对应的值
     * @param path 用"."分割的路径信息
     * @return 返回该路径对应的值，不存在则返回null
     */
    default Object getByPath(String path) {
        if (StringUtil.isEmpty(path)) {
            return null;
        }
        int start = 0;
        ParsePathInfo parsePathInfo = getPathItem(path, start);
        Object v = get(parsePathInfo.item);
        while (parsePathInfo.endPos < path.length()) {
            parsePathInfo = getPathItem(path, parsePathInfo.endPos);
            if (!(v instanceof Map)) {
                return null;
            } else {
                v = ((Map<?, ?>)v).get(parsePathInfo.item);
            }
        }
        return v;
    }

    /**
     * 按"."分割的方式获取路径的一部分，如果有已"\""开头则到下一个引号结束，如果有引号引起来的路径部分去除引号外的空格
     * @param path 路径信息
     * @param start 开始位置
     * @return 返回指定位置后并且到第一个点结束的字符串
     */
    private ParsePathInfo getPathItem(String path, int start) {
        if (path.charAt(path.length()-1) == '.') {
            throw new RuntimeException("path cann't end with '.'!");
        }
        ParsePathInfo info = new ParsePathInfo();
        char startChar = path.charAt(start);
        int len = path.length();
        if (startChar == '"') {
            start++;
            int i = start;
            for (;i<len;i++) {
                char c = path.charAt(i);
                if (c == '"' && i > start && path.charAt(i - 1) != '\\') {
                    info.item = path.substring(start, i);
                    break;
                }
            }
            if (info.item == null) {
                throw new RuntimeException("path did not end normally!");
            }
            for (;i<len;i++) {
                char c = path.charAt(i);
                if (c == ' ' || c == '\t') {
                    continue;
                }
                if (c == '.') {
                    info.endPos = i+1;
                    return info;
                }
            }
            info.endPos = i;
        } else {
            int dotIndex = path.indexOf('.', start);
            if (dotIndex == -1) {
                info.item = path.substring(start);
                info.endPos = path.length();
            } else {
                info.item = path.substring(start, dotIndex);
                info.endPos = dotIndex + 1;
            }
        }
        return info;
    }

    class ParsePathInfo {
        public String item;
        public int endPos;
    }

}
