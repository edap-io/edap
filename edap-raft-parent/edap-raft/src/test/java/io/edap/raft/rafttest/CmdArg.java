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

package io.edap.raft.rafttest;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 命令行参数，等价于 cockroachdb/datadriven 的 {@code CmdArg}。
 *
 * <p>参数有两种形态：</p>
 * <ul>
 *     <li>{@code key}：只有名字，没有值，{@link #getVals()} 是空列表</li>
 *     <li>{@code key=value}：有一个值</li>
 *     <li>{@code key=(v1,v2)}：有一组值，取值来自括号里按逗号分隔的内容</li>
 * </ul>
 */
public class CmdArg {

    private final String       key;
    private final List<String> vals;

    public CmdArg(String key, List<String> vals) {
        this.key = key;
        this.vals = vals == null ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(vals));
    }

    /**
     * 参数名
     */
    public String getKey() {
        return key;
    }

    /**
     * 参数值，没有值时是空列表
     */
    public List<String> getVals() {
        return vals;
    }

    /**
     * 第 i 个值按无符号整数解析
     */
    public long scanLong(int i) {
        String v = val(i);
        try {
            return Long.parseLong(v);
        } catch (NumberFormatException e) {
            throw new DataDrivenException("cannot parse " + v + " as an integer");
        }
    }

    /**
     * 第 i 个值按整数解析
     */
    public int scanInt(int i) {
        String v = val(i);
        try {
            return Integer.parseInt(v);
        } catch (NumberFormatException e) {
            throw new DataDrivenException("cannot parse " + v + " as an integer");
        }
    }

    /**
     * 第 i 个值按布尔量解析。
     *
     * <p>与 Go 的 {@code strconv.ParseBool} 一致，
     * 接受 {@code 1 t T TRUE true True} 与 {@code 0 f F FALSE false False}。</p>
     */
    public boolean scanBool(int i) {
        String v = val(i);
        if ("1".equals(v) || "t".equals(v) || "T".equals(v)
                || "true".equals(v) || "TRUE".equals(v) || "True".equals(v)) {
            return true;
        }
        if ("0".equals(v) || "f".equals(v) || "F".equals(v)
                || "false".equals(v) || "FALSE".equals(v) || "False".equals(v)) {
            return false;
        }
        throw new DataDrivenException("cannot parse " + v + " as a boolean");
    }

    /**
     * 第 i 个值按原始字节解析
     */
    public byte[] scanBytes(int i) {
        return val(i).getBytes(StandardCharsets.UTF_8);
    }

    /**
     * 第 i 个值按字符串解析
     */
    public String scanString(int i) {
        return val(i);
    }

    private String val(int i) {
        if (i < 0 || i >= vals.size()) {
            throw new DataDrivenException("index " + i + " out of range for argument " + key);
        }
        return vals.get(i);
    }

    @Override
    public String toString() {
        return key + "=" + vals;
    }
}
