package io.edap.mqtt.property.test;

import io.edap.mqtt.PropertyType;
import io.edap.mqtt.property.SessionExpiryInterval;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

public class SessionExpiryIntervalTest {

    @Test
    public void testName() {
        SessionExpiryInterval sei = new SessionExpiryInterval();
        assertEquals(sei.name(), "Session Expiry Interval");
    }

    @Test
    public void testIdentifier() {
        SessionExpiryInterval sei = new SessionExpiryInterval();
        assertEquals(sei.identifier(), PropertyType.SESSION_EXPIRY_INTERVAL.getType());
    }

    @Test
    public void testValue() {
        SessionExpiryInterval sei = new SessionExpiryInterval();
        assertNotNull(sei.value());
        assertEquals(sei.value().intValue(), 0);

        Integer value = new Random().nextInt();
        sei.value(value);
        assertEquals(sei.value().intValue(), value.intValue());
    }
}
