package io.edap.mqtt.property.test;

import io.edap.mqtt.PropertyType;
import io.edap.mqtt.property.MaximumQoS;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

public class MaximumQoSTest {

    @Test
    public void testName() {
        MaximumQoS mq = new MaximumQoS();
        assertEquals(mq.name(), "Maximum QoS");
    }

    @Test
    public void testIdentifier() {
        MaximumQoS mq = new MaximumQoS();
        assertEquals(mq.identifier(), PropertyType.MAXIMUM_QOS.getType());
    }

    @Test
    public void testValue() {
        MaximumQoS mq = new MaximumQoS();
        assertNotNull(mq.value());
        Byte value = (byte) new Random().nextInt(Byte.MAX_VALUE);
        mq.value(value);
        assertEquals(mq.value().byteValue(), value.byteValue());
    }
}
