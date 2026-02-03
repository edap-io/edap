package io.edap.mqtt.property.test;

import io.edap.mqtt.PropertyType;
import io.edap.mqtt.property.RetainAvailable;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

public class RetainAvailableTest {

    @Test
    public void testName() {
        RetainAvailable ra = new RetainAvailable();
        assertEquals(ra.name(), "Retain Available");
    }

    @Test
    public void testIdentifier() {
        RetainAvailable ra = new RetainAvailable();
        assertEquals(ra.identifier(), PropertyType.RETAIN_AVAILABLE.getType());
    }

    @Test
    public void testValue() {
        RetainAvailable ra = new RetainAvailable();
        assertNotNull(ra.value());
        assertEquals(ra.value().byteValue(), 0);
        Byte value = (byte)new Random().nextInt(Byte.MAX_VALUE);
        ra.value(value);
        assertEquals(ra.value().byteValue(), value.byteValue());
    }
}
