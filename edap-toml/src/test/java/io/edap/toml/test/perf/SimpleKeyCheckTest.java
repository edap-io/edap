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

package io.edap.toml.test.perf;

import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import static io.edap.toml.Toml.isSimpleKey;

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Thread)
@Threads(10)
public class SimpleKeyCheckTest {

    Pattern simpleKeyPattern;
    String key;

    static boolean[] SIMPLE_KEY = new boolean[123];
    static {
        for (int i=0;i<123;i++) {
            SIMPLE_KEY[i] = false;
        }
        int max = 0;
        for (int i='a';i<='z';i++) {
            if (i > max) {
                max = i;
            }
            SIMPLE_KEY[i] = true;
        }
        for (int i='A';i<='Z';i++) {
            SIMPLE_KEY[i] = true;
            if (i > max) {
                max = i;
            }
        }
        for (int i='0';i<='9';i++) {
            SIMPLE_KEY[i] = true;
            if (i > max) {
                max = i;
            }
        }
        SIMPLE_KEY['-'] = true;
        SIMPLE_KEY['_'] = true;
        System.out.println("max=" + max);
    }

    @Setup
    public void setUp() {
        simpleKeyPattern = Pattern.compile("^[A-Za-z0-9_-]+$");
        key = "characterencodingcharacterencodingcharacterencodingcharacterencodingcharacterencodingcharacterencoding";
    }

    @Benchmark
    public void patternCheck() {
        simpleKeyPattern.matcher(key).matches();
    }

    @Benchmark
    public void charLoopCheck() {
        isSimpleKey(key);
    }

    public static void main(String[] args) throws RunnerException {

        Options opt = new OptionsBuilder()
                .include(SimpleKeyCheckTest.class.getSimpleName())
                .forks(1)
                .warmupIterations(3)
                .measurementIterations(5)
                //.addProfiler(StackProfiler.class)
                .build();

        new Runner(opt).run();
    }
}
