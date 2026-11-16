package io.edap.container.test;

import io.edap.container.AppContext;
import io.edap.container.Container;
import io.edap.util.UnsafeUtil;
import io.edap.util.timing.TimerHandle;
import io.edap.util.timing.Timers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PREVIOUS 槽延迟释放 —— 接入平台时间轮后的行为。
 *
 * <p>PREVIOUS 槽的延迟 stop 以前跑在 Container 自己的
 * {@code ScheduledExecutorService} 上，现在跑在平台共享时间轮
 * （{@link Timers}）上。这里验两件事：</p>
 *
 * <ol>
 *   <li>延迟任务确实挂在平台时间轮上（句柄挂到 ctx 的 releaseFuture 上）；</li>
 *   <li>{@code cancelRelease()} 能把延迟任务撤掉 —— 下一次 switchVersion
 *       要 demote 新 current 时，旧 PREVIOUS 必须能被提前释放。</li>
 * </ol>
 *
 * <p><b>为什么用 Unsafe 造 AppContext</b>：AppContext 的构造要一个真实的
 * {@code EdapAppClassLoader}（要一个 jar 文件），单元测试里造不出来；
 * 而这里要验的只是 releaseFuture 这个句柄的存取与取消，
 * 跟 AppContext 的其余生命周期无关。</p>
 */
public class PrevReleaseTimerTest {

    @AfterEach
    public void tearDown() {
        Timers.close();
    }

    @Test
    public void prevReleaseIsScheduledOnPlatformTimer() throws Exception {
        Container container = new Container(new File("ignored"), Set.of());
        setPrevReleaseDelay(container, 60_000L);

        AppContext ctx = newBlankContext();
        schedulePrevRelease(container, ctx);

        TimerHandle handle = releaseFuture(ctx);
        assertNotNull(handle, "延迟释放的句柄必须挂到 ctx 上");
        assertTrue(Timers.get().pendingCount() > 0, "任务必须挂在平台时间轮上");
    }

    @Test
    public void cancelReleasePreventsDelayedStop() throws Exception {
        Container container = new Container(new File("ignored"), Set.of());
        setPrevReleaseDelay(container, 60_000L);

        AppContext ctx = newBlankContext();
        schedulePrevRelease(container, ctx);

        TimerHandle handle = releaseFuture(ctx);
        ctx.cancelRelease();

        assertTrue(handle.isCancelled(), "cancelRelease 必须把平台时间轮上的任务撤掉");
        assertEquals(0, Timers.get().pendingCount(), "撤掉之后平台时间轮上不该还挂着这个任务");
    }

    /**
     * 空 AppContext —— 跳过构造函数（详见类注释）。
     */
    private static AppContext newBlankContext() throws Exception {
        return (AppContext) UnsafeUtil.allocateInstance(AppContext.class);
    }

    private static void setPrevReleaseDelay(Container container, long delayMs) throws Exception {
        Field f = Container.class.getDeclaredField("prevReleaseDelayMs");
        f.setAccessible(true);
        f.set(container, delayMs);
    }

    private static void schedulePrevRelease(Container container, AppContext ctx) throws Exception {
        Method m = Container.class.getDeclaredMethod("schedulePrevRelease", AppContext.class);
        m.setAccessible(true);
        m.invoke(container, ctx);
    }

    private static TimerHandle releaseFuture(AppContext ctx) throws Exception {
        Field f = AppContext.class.getDeclaredField("releaseFuture");
        f.setAccessible(true);
        return (TimerHandle) f.get(ctx);
    }
}
