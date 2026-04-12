package io.edap.mqtt.property.test;

import io.edap.mqtt.PropertyType;
import io.edap.mqtt.property.SubscriptionIdentifierAvailable;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

public class SubscriptionIdentifierAvailableTest {

    @Test
    public void testName() {
        SubscriptionIdentifierAvailable sia = new SubscriptionIdentifierAvailable();
        assertEquals(sia.name(), "Subscription Identifier Available");
    }

    @Test
    public void testIdentifier() {
        SubscriptionIdentifierAvailable sia = new SubscriptionIdentifierAvailable();
        assertEquals(sia.identifier(), PropertyType.SUBSCRIPTION_INDENTIFIER_AVAILABLE.getType());
    }

    @Test
    public void testValue() {
        SubscriptionIdentifierAvailable sia = new SubscriptionIdentifierAvailable();
        assertNotNull(sia.value());
        assertEquals(sia.value().byteValue(), 0);

        Byte value = (byte)new Random().nextInt(Byte.MAX_VALUE);
        sia.value(value);
        assertEquals(sia.value().byteValue(), value.byteValue());
    }
}
