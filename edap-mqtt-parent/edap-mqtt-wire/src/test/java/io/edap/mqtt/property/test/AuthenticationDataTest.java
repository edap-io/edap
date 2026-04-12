package io.edap.mqtt.property.test;

import io.edap.mqtt.PacketProperty;
import io.edap.mqtt.PropertyType;
import io.edap.mqtt.property.AuthenticationData;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Random;

import static io.edap.mqtt.packet.test.ConnectTest.randomStr;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class AuthenticationDataTest {

    @Test
    public void testName() {
        AuthenticationData ad = new AuthenticationData();
        assertEquals(ad.name(), "Authentication Data");
    }

    @Test
    public void testIdentifier() {
        AuthenticationData ad = new AuthenticationData();
        assertEquals(ad.identifier(), PropertyType.AUTHENTICATION_DATA.getType());
    }

    @Test
    public void testValue() {
        AuthenticationData ad = new AuthenticationData();
        byte[] data = randomStr(new Random().nextInt(1024)).getBytes(StandardCharsets.UTF_8);
        ad.value(data);
        assertArrayEquals(data, ad.value());
    }
}
