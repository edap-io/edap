package io.edap.nio.nativeimpl.test;

import io.edap.nio.nativeimpl.FastNetIO;
import org.junit.jupiter.api.Test;

public class FastNetIOTest {

    @Test
    public void testInit() {
        FastNetIO.isEnableNativeRw();
    }
}
