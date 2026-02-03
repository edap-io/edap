package io.edap.mqtt.packet.test;

import io.edap.mqtt.ProtocolLevel;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class ProtocolLevelTest {

    @Test
    public void testConstructor() {
        ProtocolLevel unknown = ProtocolLevel.UNKNOWN;
        ProtocolLevel v3_1 = ProtocolLevel.VERSION_3_1;
        ProtocolLevel v3_1_1 = ProtocolLevel.VERSION_3_1_1;
        ProtocolLevel v5 = ProtocolLevel.VERSION_5;
        assertEquals(unknown.getValue(), 0);
        assertEquals(v3_1.getValue(), 3);
        assertEquals(v3_1_1.getValue(), 4);
        assertEquals(v5.getValue(), 5);
    }

    @Test
    public void testFromValue() {
        ProtocolLevel level = ProtocolLevel.fromValue(0);
        assertEquals(level, ProtocolLevel.UNKNOWN);
        level = ProtocolLevel.fromValue(1);
        assertEquals(level, ProtocolLevel.UNKNOWN);
        level = ProtocolLevel.fromValue(2);
        assertEquals(level, ProtocolLevel.UNKNOWN);
        level = ProtocolLevel.fromValue(3);
        assertEquals(level, ProtocolLevel.VERSION_3_1);
        level = ProtocolLevel.fromValue(4);
        assertEquals(level, ProtocolLevel.VERSION_3_1_1);
        level = ProtocolLevel.fromValue(5);
        assertEquals(level, ProtocolLevel.VERSION_5);

        level = ProtocolLevel.fromValue(6);
        assertEquals(level, ProtocolLevel.UNKNOWN);
        level = ProtocolLevel.fromValue(-1);
        assertEquals(level, ProtocolLevel.UNKNOWN);
    }
}
