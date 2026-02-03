package io.edap.mqtt.property.test;

import io.edap.mqtt.PropertyType;
import io.edap.mqtt.property.ReceiveMaximum;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

public class ReceiveMaximumTest {

    @Test
    public void testName() {
        ReceiveMaximum rm = new ReceiveMaximum();
        assertEquals(rm.name(), "Receive Maximum");
    }

    @Test
    public void testIdentifier() {
        ReceiveMaximum rm = new ReceiveMaximum();
        assertEquals(rm.identifier(), PropertyType.RECEIVE_MAXINUM.getType());
    }

    @Test
    public void testValue() {
        ReceiveMaximum rm = new ReceiveMaximum();
        assertNotNull(rm.value());
        assertEquals(rm.value().shortValue(), 0);
        Integer value = new Random().nextInt(Short.MAX_VALUE);
        rm.value(value);
        assertEquals(rm.value().shortValue(), value.shortValue());
    }
}
