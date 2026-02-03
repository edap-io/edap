package io.edap.mqtt.property.test;

import io.edap.mqtt.PropertyType;
import io.edap.mqtt.property.WillDelayInterval;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

public class WillDelayIntervalTest {

    @Test
    public void testName() {
        WillDelayInterval wi = new WillDelayInterval();
        assertEquals(wi.name(), "Will Delay Interval");
    }

    @Test
    public void testIdentifier() {
        WillDelayInterval wi = new WillDelayInterval();
        assertEquals(wi.identifier(), PropertyType.WILL_DELAY_INTERVAL.getType());
    }

    @Test
    public void testValue() {
        WillDelayInterval wi = new WillDelayInterval();
        assertNotNull(wi.value());
        assertEquals(wi.value().intValue(), 0);
        Integer value = new Random().nextInt();
        wi.value(value);
        assertEquals(wi.value().intValue(), value.intValue());
    }
}
