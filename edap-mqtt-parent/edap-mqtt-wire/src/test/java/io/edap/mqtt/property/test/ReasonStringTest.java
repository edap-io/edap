package io.edap.mqtt.property.test;

import io.edap.mqtt.PropertyType;
import io.edap.mqtt.property.ReasonString;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static io.edap.mqtt.packet.test.ConnectTest.randomStr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

public class ReasonStringTest {

    @Test
    public void testName() {
        ReasonString rs = new ReasonString();
        assertEquals(rs.name(), "Reason String");
    }

    @Test
    public void testIdentifier() {
        ReasonString rs = new ReasonString();
        assertEquals(rs.identifier(), PropertyType.REASON_STRING.getType());
    }

    @Test
    public void testValue() {
        ReasonString rs = new ReasonString();
        assertNull(rs.value());
        String value = randomStr(5 + new Random().nextInt(20));
        rs.value(value);
        assertEquals(rs.value(), value);
    }
}
