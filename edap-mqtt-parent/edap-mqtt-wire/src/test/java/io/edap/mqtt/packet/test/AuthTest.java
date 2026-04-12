package io.edap.mqtt.packet.test;

import io.edap.mqtt.PacketProperty;
import io.edap.mqtt.PropertyType;
import io.edap.mqtt.packet.Auth;
import io.edap.mqtt.ControlPacketType;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

public class AuthTest {

    @Test
    public void testConstuct() {
        Auth auth = new Auth(50);
        assertEquals(auth.getType(), ControlPacketType.AUTH);
    }

    @Test
    public void testReasonCode() {
        Auth auth = new Auth(50);
        int code = new Random().nextInt(Byte.MAX_VALUE);
        auth.setReasonCode(code);
        assertEquals(auth.getReasonCode(), code);
    }

    @Test
    public void testProperties() {
        Auth auth = new Auth(50);
        LinkedHashMap<PropertyType, PacketProperty> props = new LinkedHashMap<>();
        assertNull(auth.getProperties());
        auth.setProperties(props);
        assertEquals(auth.getProperties().size(), 0);

    }

}
