package io.edap.log.test.perf;

import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;


@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Thread)
@Threads(10)
public class WriteDataPerf {

    ReentrantLock lock = new ReentrantLock(false);

    byte[] data = ("Blackhole mode: full + dont-inline hint (auto-detected, use " +
            "-Djmh.blackhole.autoDetect=false to disable)").getBytes(StandardCharsets.UTF_8);

    BufferedOutputStream bufOut;

    FileOutputStream outputFos;

    FileChannel fileChannel;

    @Setup
    public void setUp() throws IOException {
        cleanFile();

        outputFos = new FileOutputStream("output.log", true);
        bufOut = new BufferedOutputStream(outputFos, 8192);

        fileChannel = new FileOutputStream("channel.log", true).getChannel();
    }

    private void cleanFile() throws IOException {
        File f = new File("outout.log");
        if (f.exists()) {
            f.delete();
        }
        f.createNewFile();
        File chan = new File("channel.log");
        if (chan.exists()) {
            chan.delete();
        }
        chan.createNewFile();
    }

    @Benchmark
    public void outputStreamWrite() throws Exception {
        cleanFile();
        for (int i=0;i<100;i++) {
            try {
                lock.lock();
                bufOut.write(data);
                bufOut.flush();
            } finally {
                lock.unlock();
            }
        }
    }

    @Benchmark
    public void channelWrite() throws IOException {
        cleanFile();
        for (int i=0;i<100;i++) {
            fileChannel.write(ByteBuffer.wrap(data));
        }
    }

    public static void main(String[] args) throws RunnerException {

        Options opt = new OptionsBuilder()
                .include(WriteDataPerf.class.getSimpleName())
                .forks(1)
                .warmupIterations(3)
                .measurementIterations(5)
                //.addProfiler(StackProfiler.class)
                .build();

        new Runner(opt).run();
    }
}
