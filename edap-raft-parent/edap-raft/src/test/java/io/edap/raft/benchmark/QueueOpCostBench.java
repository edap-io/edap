package io.edap.raft.benchmark;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

import java.util.concurrent.TimeUnit;

/**
 * 队列「单次操作」的纯成本对比：一次 offer + 一次 poll，不含线程切换。
 *
 * <p>这一项只回答「一次入队出队本身要多少时间」，
 * 不回答「MPSC 下会不会退化」——那部分由 {@link MpscHarness} 和
 * {@link LatencyHarness} 覆盖。</p>
 *
 * <p>跑法见 {@link QueueThroughputBench} 的类注释。</p>
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(value = 2, jvmArgsAppend = {"-Xms2g", "-Xmx2g"})
public class QueueOpCostBench {

    private static final Object PAYLOAD = new Object();

    @State(Scope.Thread)
    public static class LbqState {
        MessageBus bus;

        @Setup(Level.Iteration)
        public void setup() {
            bus = new MessageBus.LinkedBlockingBus(1, 8192);
        }

        @TearDown(Level.Iteration)
        public void tearDown() {
            bus.stop();
        }
    }

    @State(Scope.Thread)
    public static class DisruptorState {
        MessageBus bus;

        @Setup(Level.Iteration)
        public void setup() {
            bus = new MessageBus.DisruptorBus(1, 8192);
        }

        @TearDown(Level.Iteration)
        public void tearDown() {
            bus.stop();
        }
    }

    @State(Scope.Thread)
    public static class VmsmithState {
        MessageBus bus;

        @Setup(Level.Iteration)
        public void setup() {
            bus = new MessageBus.VmsmithBus(1, 1024, 8192, true);
            bus.start();
        }

        @TearDown(Level.Iteration)
        public void tearDown() {
            bus.stop();
        }
    }

    @State(Scope.Thread)
    public static class FanInState {
        MessageBus bus;

        @Setup(Level.Iteration)
        public void setup() {
            bus = new MessageBus.FanInBus(1, 1024, true);
        }

        @TearDown(Level.Iteration)
        public void tearDown() {
            bus.stop();
        }
    }

    @Benchmark
    public void lbq(LbqState s) {
        s.bus.publish(0, PAYLOAD);
        s.bus.take();
    }

    @Benchmark
    public void disruptor(DisruptorState s) {
        s.bus.publish(0, PAYLOAD);
        s.bus.take();
    }

    @Benchmark
    public void vmsmith(VmsmithState s) {
        s.bus.publish(0, PAYLOAD);
        s.bus.take();
    }

    @Benchmark
    public void fanin(FanInState s) {
        s.bus.publish(0, PAYLOAD);
        s.bus.take();
    }
}
