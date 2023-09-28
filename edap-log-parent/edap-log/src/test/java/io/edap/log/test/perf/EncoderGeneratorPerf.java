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

package io.edap.log.test.perf;

import io.edap.log.converter.CacheDateFormatterConverter;
import io.edap.log.converter.DateFormatterConverter;
import io.edap.log.helps.ByteArrayBuilder;
import io.edap.log.helps.EncoderGenerator;
import io.edap.log.helps.TextEncoderGenerator;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import java.util.concurrent.TimeUnit;

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Thread)
@Threads(10)
public class EncoderGeneratorPerf {

    static String text = "] - ";

    TextEncoderGenerator textEncoderGenerator;
    TextEncoderCalGenerator textEncoderCalGenerator;

    @Setup
    public void setUp() {
        textEncoderGenerator = new TextEncoderGenerator(text);
        textEncoderCalGenerator = new TextEncoderCalGenerator(text);
    }

    @Benchmark
    public void generate() throws Exception {
        textEncoderGenerator.getClassInfo();
    }

    @Benchmark
    public void calGenerate() throws Exception {
        textEncoderCalGenerator.getClassInfo();
    }

    public static void main(String[] args) throws RunnerException {

        Options opt = new OptionsBuilder()
                .include(EncoderGeneratorPerf.class.getSimpleName())
                .forks(1)
                .warmupIterations(3)
                .measurementIterations(5)
                //.addProfiler(StackProfiler.class)
                .build();

        new Runner(opt).run();
    }
}
