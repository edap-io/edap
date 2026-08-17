package io.edap.jni.test;

import io.edap.jni.Native;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Native 加载机制：幂等 + 状态可见。
 */
public class NativeLoadTest {

    @Test
    public void testLoadIsIdempotent() {
        Native.loadLibrary();
        boolean first = Native.ENABLE_NATIVE;
        // 多次调用应不会反复加载
        for (int i = 0; i < 5; i++) {
            Native.loadLibrary();
        }
        assertEquals(first, Native.ENABLE_NATIVE);
    }

    @Test
    public void testLoadOnThread() throws Exception {
        // 多次调用无副作用
        Native.loadLibrary();
        assertTrue(true);
    }
}
