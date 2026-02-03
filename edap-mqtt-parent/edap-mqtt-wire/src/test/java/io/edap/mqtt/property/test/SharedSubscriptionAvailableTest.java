package io.edap.mqtt.property.test;

import io.edap.mqtt.PropertyType;
import io.edap.mqtt.property.SharedSubscriptionAvailable;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

public class SharedSubscriptionAvailableTest {

    @Test
    public void testName() {
        SharedSubscriptionAvailable ssa = new SharedSubscriptionAvailable();
        assertEquals(ssa.name(), "Shared Subscription Available");
    }

    @Test
    public void testIdentifier() {
        SharedSubscriptionAvailable ssa = new SharedSubscriptionAvailable();
        assertEquals(ssa.identifier(), PropertyType.SHARED_SUBSCRIPTION_AVAILABLE.getType());
    }

    @Test
    public void testValue() {
        SharedSubscriptionAvailable ssa = new SharedSubscriptionAvailable();
        assertNotNull(ssa.value());
        assertEquals(ssa.value().byteValue(), 0);

        Byte value = (byte)new Random().nextInt(Byte.MAX_VALUE);
        ssa.value(value);
        assertEquals(ssa.value().byteValue(), value.byteValue());
    }
}
