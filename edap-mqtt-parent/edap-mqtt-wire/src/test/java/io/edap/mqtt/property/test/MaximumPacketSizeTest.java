package io.edap.mqtt.property.test;

import io.edap.mqtt.PropertyType;
import io.edap.mqtt.property.MaximumPacketSize;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

public class MaximumPacketSizeTest {

    @Test
    public void testName() {
        MaximumPacketSize mps = new MaximumPacketSize();
        assertEquals(mps.name(), "Maximum Packet Size");
    }

    @Test
    public void testIdentifier() {
        MaximumPacketSize mps = new MaximumPacketSize();
        assertEquals(mps.identifier(), PropertyType.MAXIMUM_PACKET_SIZE.getType());
    }

    @Test
    public void testValue() {
        MaximumPacketSize mps = new MaximumPacketSize();
        assertNotNull(mps.value());
        Integer value = new Random().nextInt();
        mps.value(value);
        assertEquals(mps.value().intValue(), value.intValue());
    }
}
