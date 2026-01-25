package io.edap.mqtt.test.wire.test;

import io.edap.mqtt.wire.ControlPacketType;
import io.edap.mqtt.wire.Disconnect;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class DisconnectTest {

    @Test
    public void testConstructor() {
        Disconnect disconnect = new Disconnect(53);
        assertEquals(disconnect.getType(), ControlPacketType.DISCONNECT);
    }
}
