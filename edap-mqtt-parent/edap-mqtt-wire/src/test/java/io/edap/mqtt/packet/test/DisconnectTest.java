package io.edap.mqtt.packet.test;

import io.edap.mqtt.ControlPacketType;
import io.edap.mqtt.PacketProperty;
import io.edap.mqtt.PropertyType;
import io.edap.mqtt.packet.Disconnect;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

public class DisconnectTest {

    @Test
    public void testConstructor() {
        Disconnect disconnect = new Disconnect(53);
        assertEquals(disconnect.getType(), ControlPacketType.DISCONNECT);
    }

    @Test
    public void testReasonCode() {
        Disconnect disconnect = new Disconnect(53);
        int code = new Random().nextInt(Byte.MAX_VALUE);
        disconnect.setReasonCode(code);
        assertEquals(disconnect.getReasonCode(), code);
    }

    @Test
    public void testProperties() {
        Disconnect disconnect = new Disconnect(53);
        LinkedHashMap<PropertyType, PacketProperty> props = new LinkedHashMap<>();
        assertNull(disconnect.getProperties());
        disconnect.setProperties(props);
        assertEquals(disconnect.getProperties().size(), 0);

    }

}
