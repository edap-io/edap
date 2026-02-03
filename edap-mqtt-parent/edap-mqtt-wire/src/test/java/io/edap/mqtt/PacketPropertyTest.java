package io.edap.mqtt;

import org.junit.jupiter.api.Test;

import static io.edap.mqtt.PacketProperty.PAYLOAD_FORMAT_INDICATOR_ID;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class PacketPropertyTest {

    @Test
    public void testStatic() {
        assertEquals(PAYLOAD_FORMAT_INDICATOR_ID, 1);
    }
}
