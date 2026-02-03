package io.edap.mqtt.property.test;

import io.edap.mqtt.PropertyType;
import io.edap.mqtt.property.ServerReference;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static io.edap.mqtt.packet.test.ConnectTest.randomStr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

public class ServerReferenceTest {

    @Test
    public void testName() {
        ServerReference sr = new ServerReference();
        assertEquals(sr.name(), "Server Reference");
    }

    @Test
    public void testIdentifier() {
        ServerReference sr = new ServerReference();
        assertEquals(sr.identifier(), PropertyType.SERVER_REFERENCE.getType());
    }

    @Test
    public void testValue() {
        ServerReference sr = new ServerReference();
        assertNull(sr.value());
        String value = randomStr(5 + new Random().nextInt(30));
        sr.value(value);
        assertEquals(sr.value(), value);
    }
}
