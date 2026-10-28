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

package io.edap.raft;

import io.edap.raft.rafttest.DataDriven;
import io.edap.raft.rafttest.InteractionEnv;
import io.edap.raft.rafttest.TestData;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 交互式测试的驱动，等价于 etcd 的 {@code interaction_test.go}。
 *
 * <p>逐个跑 {@code testdata} 目录下的数据驱动测试文件：每个文件里是一串命令
 * 与期望输出，命令跑一遍，输出与期望逐字比对。</p>
 *
 * <p>测试数据来自 etcd raft v3.6 的 {@code testdata} 目录，
 * 逐字复制过来，作为 Java 实现与上游行为一致性的基准。</p>
 */
public class InteractionTest {

    /**
     * 测试数据在 classpath 里的位置
     */
    private static final String TESTDATA = "/testdata";

    /**
     * 每个测试文件一个用例
     */
    @TestFactory
    public Stream<DynamicTest> interactionTests() {
        List<Path> files = testDataFiles();
        List<DynamicTest> tests = new ArrayList<>(files.size());
        for (Path file : files) {
            tests.add(DynamicTest.dynamicTest(file.getFileName().toString(),
                    () -> runFile(file)));
        }
        return tests.stream();
    }

    /**
     * 跑一个测试文件里的所有用例
     */
    private void runFile(Path file) {
        List<TestData> cases;
        try {
            cases = DataDriven.parseFile(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        InteractionEnv env = new InteractionEnv();
        for (TestData d : cases) {
            String got = env.handle(d);
            String expected = d.getExpected();
            if (!trim(expected).equals(trim(got))) {
                throw new AssertionError(d.getPath() + ":" + d.getLine() + ": " + d.getCmd()
                        + "\n--- expected ---\n" + expected + "\n--- found ---\n" + got);
            }
        }
    }

    /**
     * 找出所有测试数据文件
     */
    private static List<Path> testDataFiles() {
        try {
            URL url = InteractionTest.class.getResource(TESTDATA);
            if (url == null) {
                throw new IllegalStateException("cannot find testdata directory on the classpath");
            }
            Path dir = Paths.get(url.toURI());
            try (Stream<Path> files = Files.walk(dir)) {
                return files.filter(Files::isRegularFile)
                        .filter(p -> p.getFileName().toString().endsWith(".txt"))
                        .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                        .collect(Collectors.toList());
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (java.net.URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * 去掉首尾空白，与 cockroachdb/datadriven 的比较方式一致
     */
    private static String trim(String s) {
        return s == null ? "" : s.trim();
    }
}
