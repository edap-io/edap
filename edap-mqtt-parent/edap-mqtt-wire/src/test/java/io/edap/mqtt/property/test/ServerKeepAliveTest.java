package io.edap.mqtt.property.test;

import io.edap.mqtt.PropertyType;
import io.edap.mqtt.property.ServerKeepAlive;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

public class ServerKeepAliveTest {

    @Test
    public void testName() {
        ServerKeepAlive ska = new ServerKeepAlive();
        assertEquals(ska.name(), "Server Keep Alive");
    }

    @Test
    public void testIdentifier() {
        ServerKeepAlive ska = new ServerKeepAlive();
        assertEquals(ska.identifier(), PropertyType.SERVER_KEEP_ALIVE.getType());
    }

    @Test
    public void testValue() {
        ServerKeepAlive ska = new ServerKeepAlive();
        assertNotNull(ska.value());
        assertEquals(ska.value().shortValue(), 0);
        Integer value = new Random().nextInt(Short.MAX_VALUE);
        ska.value(value);
        assertEquals(ska.value().shortValue(), value.shortValue());
    }
}
