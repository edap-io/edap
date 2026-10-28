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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 数据驱动测试文件的解析器，等价于 cockroachdb/datadriven 的解析部分。
 *
 * <p>文件由若干个用例组成，每个用例长这样：</p>
 *
 * <pre>
 * # 这是注释，用例之间可以随便加
 *
 * 命令名 参数1 参数2=值3
 * 输入内容（可省略）
 * ----
 * 期望输出
 * </pre>
 *
 * <p>用例之间用空行分开。命令块的第一行是命令本身，
 * 剩下的行是命令的输入。</p>
 */
public final class DataDriven {

    /** 用例的分隔线 */
    private static final String SEPARATOR = "----";

    private DataDriven() {
    }

    /**
     * 解析一个数据驱动测试文件。
     *
     * @param path 文件路径
     * @return 文件里的所有用例，按出现顺序
     * @throws IOException 读文件失败
     */
    public static List<TestData> parseFile(Path path) throws IOException {
        List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        return parse(lines, path.toString());
    }

    /**
     * 解析已经读进内存的文本。
     *
     * @param lines 文本的每一行
     * @param path  文件名，只用于报错信息
     * @return 文件里的所有用例，按出现顺序
     */
    public static List<TestData> parse(List<String> lines, String path) {
        List<TestData> cases = new ArrayList<>();
        int i = 0;
        int n = lines.size();
        while (i < n) {
            // 跳过用例之间的空行和注释
            while (i < n && (lines.get(i).trim().isEmpty() || lines.get(i).startsWith("#"))) {
                i++;
            }
            if (i >= n) {
                break;
            }
            int cmdLine = i + 1;
            // 命令块：一直到分隔线
            List<String> block = new ArrayList<>();
            while (i < n && !SEPARATOR.equals(lines.get(i).trim())) {
                block.add(lines.get(i));
                i++;
            }
            if (i >= n) {
                throw new DataDrivenException(path + ":" + cmdLine + ": missing " + SEPARATOR + " separator");
            }
            i++;
            // 命令块的第一行是「命令名 + 参数」，其余行是命令的输入
            List<String> tokens = splitTokens(block.get(0).trim());
            String cmd = tokens.isEmpty() ? "" : tokens.get(0);
            String input = String.join("\n", block.subList(1, block.size()));

            // 期望输出：一直到空行或者文件结束
            List<String> expected = new ArrayList<>();
            while (i < n && !lines.get(i).trim().isEmpty()) {
                expected.add(lines.get(i));
                i++;
            }
            cases.add(new TestData(cmd, parseCmdArgs(tokens.subList(1, tokens.size())), input,
                    String.join("\n", expected), path, cmdLine));
        }
        return cases;
    }

    /**
     * 解析命令行里的参数。
     *
     * <p>参数的形态：</p>
     * <ul>
     *     <li>{@code key}：只有名字</li>
     *     <li>{@code key=value}：一个值</li>
     *     <li>{@code key=(v1,v2)}：一组值</li>
     * </ul>
     *
     * <p>括号里的值可以按逗号或者空白分隔，
     * 所以 {@code voters=(1,2,3)} 和 {@code voters=(1 2 3)} 是一个意思。</p>
     */
    public static List<CmdArg> parseCmdArgs(List<String> tokens) {
        List<CmdArg> args = new ArrayList<>();
        for (String token : tokens) {
            int eq = token.indexOf('=');
            if (eq < 0) {
                args.add(new CmdArg(token, Collections.emptyList()));
                continue;
            }
            String key = token.substring(0, eq);
            String val = token.substring(eq + 1);
            if (val.length() >= 2 && val.charAt(0) == '(' && val.charAt(val.length() - 1) == ')') {
                args.add(new CmdArg(key, splitList(val.substring(1, val.length() - 1))));
            } else {
                args.add(new CmdArg(key, Collections.singletonList(unquote(val))));
            }
        }
        return args;
    }

    /**
     * 按空白切分命令行，括号里的空白不算分隔符。
     */
    private static List<String> splitTokens(String cmd) {
        List<String> tokens = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        int depth = 0;
        for (int i = 0; i < cmd.length(); i++) {
            char c = cmd.charAt(i);
            if (c == '(') {
                depth++;
                cur.append(c);
            } else if (c == ')') {
                depth--;
                cur.append(c);
            } else if (depth == 0 && Character.isWhitespace(c)) {
                if (cur.length() > 0) {
                    tokens.add(cur.toString());
                    cur.setLength(0);
                }
            } else {
                cur.append(c);
            }
        }
        if (cur.length() > 0) {
            tokens.add(cur.toString());
        }
        return tokens;
    }

    /**
     * 括号里的内容按逗号或者空白切成一组值。
     */
    private static List<String> splitList(String inner) {
        List<String> vals = new ArrayList<>();
        for (String part : inner.split("[,\\s]+")) {
            if (!part.isEmpty()) {
                vals.add(unquote(part));
            }
        }
        return vals;
    }

    /**
     * 去掉值外面的一层引号。
     */
    private static String unquote(String s) {
        if (s.length() >= 2 && s.charAt(0) == '"' && s.charAt(s.length() - 1) == '"') {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }
}
