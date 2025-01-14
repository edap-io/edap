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

import io.edap.log.LogEvent;
import io.edap.log.converter.CacheDateFormatterConverter;
import io.edap.log.converter.DateFormatterConverter;
import io.edap.log.helps.ByteArrayBuilder;
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
public class DateFormatTest {

    CacheDateFormatterConverter cacheDateConverter;
    DateConverterSync dateSyncConverter;

    SimpleDateConverter simpleDateConverter;

    LogbackCacheDateConverter logbackCacheDateConverter;

    DateFormatterConverter simpleDateFormatterConverter;
    ByteArrayBuilder builder;

    @Setup
    public void setUp() {
        String format = "yyyy-MM-dd HH:mm:ss.SSS";
        cacheDateConverter = new CacheDateFormatterConverter(format);
        dateSyncConverter = new DateConverterSync(format);
        simpleDateConverter = new SimpleDateConverter(format);
        logbackCacheDateConverter = new LogbackCacheDateConverter(format);
        simpleDateFormatterConverter = new DateFormatterConverter(format);
        builder = new ByteArrayBuilder();
    }

//    @Benchmark
//    public void dateFormat() throws Exception {
//        long now = System.currentTimeMillis();
//        for (int i=0;i<100;i++) {
//            builder.reset();
//            cacheDateConverter.convertTo(builder, now+i/20);
//        }
//    }
//
//    @Benchmark
//    public void dateFormatSameTime() throws Exception {
//        long now = System.currentTimeMillis();
//        for (int i=0;i<100;i++) {
//            builder.reset();
//            cacheDateConverter.convertTo(builder, now);
//        }
//    }
//
//    @Benchmark
//    public void dateFormatDiffTime() throws Exception {
//        long now = System.currentTimeMillis();
//        for (int i=0;i<100;i++) {
//            builder.reset();
//            cacheDateConverter.convertTo(builder, now + i);
//        }
//    }

    @Benchmark
    public void simpleDateFormat() throws Exception {
        long now = System.currentTimeMillis();
        LogEvent event = new LogEvent();
        for (int i=0;i<100;i++) {
            builder.reset();
            event.setLogTime(now);
            simpleDateConverter.convertTo(builder, event);
        }
    }

    @Benchmark
    public void simpleDateFormatSameTime() throws Exception {
        long now = System.currentTimeMillis();
        LogEvent event = new LogEvent();
        for (int i=0;i<100;i++) {
            builder.reset();
            event.setLogTime(now);
            simpleDateConverter.convertTo(builder, event);
        }
    }

    @Benchmark
    public void simpleDateFormatDiffTime() throws Exception {
        long now = System.currentTimeMillis();
        LogEvent event = new LogEvent();

        for (int i=0;i<100;i++) {
            builder.reset();
            event.setLogTime(now + i);
            simpleDateConverter.convertTo(builder, event);
        }
    }

    @Benchmark
    public void simpleDateFormatterFormat() throws Exception {
        long now = System.currentTimeMillis();
        LogEvent logEvent = new LogEvent();
        for (int i=0;i<100;i++) {
            builder.reset();
            logEvent.setLogTime(now+i/20);
            simpleDateFormatterConverter.convertTo(builder, logEvent);
        }
    }

    @Benchmark
    public void simpleDateFormatterFormatSameTime() throws Exception {
        long now = System.currentTimeMillis();
        LogEvent logEvent = new LogEvent();
        for (int i=0;i<100;i++) {
            builder.reset();
            logEvent.setLogTime(now);
            simpleDateFormatterConverter.convertTo(builder, logEvent);
        }
    }

    @Benchmark
    public void simpleDateFormatterFormatDiffTime() throws Exception {
        long now = System.currentTimeMillis();
        LogEvent logEvent = new LogEvent();
        for (int i=0;i<100;i++) {
            builder.reset();
            logEvent.setLogTime(now);
            simpleDateFormatterConverter.convertTo(builder, logEvent);
        }
    }
//
//    @Benchmark
//    public void dateFormatSync() throws Exception {
//        long now = System.currentTimeMillis();
//        for (int i=0;i<100;i++) {
//            builder.reset();
//            dateSyncConverter.convertTo(builder, now+i/20);
//        }
//    }
//
//    @Benchmark
//    public void dateFormatSyncSameTime() throws Exception {
//        long now = System.currentTimeMillis();
//        for (int i=0;i<100;i++) {
//            builder.reset();
//            dateSyncConverter.convertTo(builder, now);
//        }
//    }

//    @Benchmark
//    public void dateFormatSyncDiffTime() throws Exception {
//        long now = System.currentTimeMillis();
//        for (int i=0;i<100;i++) {
//            builder.reset();
//            dateSyncConverter.convertTo(builder, now + i);
//        }
//    }

//    @Benchmark
//    public void logbackDateFormat() throws Exception {
//        long now = System.currentTimeMillis();
//        for (int i=0;i<100;i++) {
//            builder.reset();
//            logbackCacheDateConverter.convertTo(builder, now+i/20);
//        }
//    }
//
//    @Benchmark
//    public void logbackDateFormatSameTime() throws Exception {
//        long now = System.currentTimeMillis();
//        for (int i=0;i<100;i++) {
//            builder.reset();
//            logbackCacheDateConverter.convertTo(builder, now);
//        }
//    }
//
//    @Benchmark
//    public void logbackDateFormatDiffTime() throws Exception {
//        long now = System.currentTimeMillis();
//        for (int i=0;i<100;i++) {
//            builder.reset();
//            logbackCacheDateConverter.convertTo(builder, now + i);
//        }
//    }

    public static void main(String[] args) throws RunnerException {

        Options opt = new OptionsBuilder()
                .include(DateFormatTest.class.getSimpleName())
                .forks(1)
                .warmupIterations(3)
                .measurementIterations(5)
                //.addProfiler(StackProfiler.class)
                .build();

        new Runner(opt).run();
    }
}
