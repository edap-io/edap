package io.edap.common.test.concurrent;

import io.edap.concurrent.Capacity;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class CapacityTest {

    @Test
    public void testOne() {
        assertEquals(1, Capacity.getCapacity(1));
    }

    @Test
    public void testThree() {

        assertEquals(4, Capacity.getCapacity(3));
    }

    @Test
    public void testIntMax() {

        assertEquals(Capacity.MAX_POWER2, Capacity.getCapacity(Integer.MAX_VALUE));
    }


    @Test
    public void testIntMax2() {

        assertEquals(Capacity.MAX_POWER2, Capacity.getCapacity(Integer.MAX_VALUE/2));
    }

    @Test
    public void testIntMax2Plus1() {

        assertEquals(Capacity.MAX_POWER2, Capacity.getCapacity(Integer.MAX_VALUE/2+1));
    }

    @Test
    public void testNegative() {

        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> {
                    Capacity.getCapacity(-9);
                });
        assertTrue(thrown.getMessage().contains("Capacity is not a power of 2."));
    }

}
