package io.edap.mqtt.property.test;

import io.edap.mqtt.PropertyType;
import io.edap.mqtt.property.MessageExpiryInterval;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

public class MessageExpiryIntervalTest {

    @Test
    public void testName() {
        MessageExpiryInterval mei = new MessageExpiryInterval();
        assertEquals(mei.name(), "Message Expiry Interval");
    }

    @Test
    public void testIdentifier() {
        MessageExpiryInterval mei = new MessageExpiryInterval();
        assertEquals(mei.identifier(), PropertyType.MESSAGE_EXPIRY_INTERVAL.getType());
    }

    @Test
    public void testValue() {
        MessageExpiryInterval mei = new MessageExpiryInterval();
        assertNotNull(mei.value());
        Integer value = new Random().nextInt();
        mei.value(value);
        assertEquals(mei.value().intValue(), value.intValue());
    }
}
