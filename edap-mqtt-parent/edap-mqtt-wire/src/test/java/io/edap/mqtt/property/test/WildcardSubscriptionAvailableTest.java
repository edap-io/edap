package io.edap.mqtt.property.test;

import io.edap.mqtt.PropertyType;
import io.edap.mqtt.property.WildcardSubscriptionAvailable;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

public class WildcardSubscriptionAvailableTest {

    @Test
    public void testName() {
        WildcardSubscriptionAvailable wsa = new WildcardSubscriptionAvailable();
        assertEquals(wsa.name(), "Wildcard Subscription Available");
    }

    @Test
    public void testIdentifier() {
        WildcardSubscriptionAvailable wsa = new WildcardSubscriptionAvailable();
        assertEquals(wsa.identifier(), PropertyType.WILDCARD_SUBSCRIPTION_AVAILABLE.getType());
    }

    @Test
    public void testValue() {
        WildcardSubscriptionAvailable wsa = new WildcardSubscriptionAvailable();
        assertNotNull(wsa.value());
        assertEquals(wsa.value().byteValue(), 0);
        Byte value = (byte)new Random().nextInt(Byte.MAX_VALUE);
        wsa.value(value);
        assertEquals(wsa.value().byteValue(), value.byteValue());
    }
}
