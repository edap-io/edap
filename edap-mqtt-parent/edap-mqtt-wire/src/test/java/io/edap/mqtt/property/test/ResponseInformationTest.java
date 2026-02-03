package io.edap.mqtt.property.test;

import io.edap.mqtt.PropertyType;
import io.edap.mqtt.property.ResponseInformation;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static io.edap.mqtt.packet.test.ConnectTest.randomStr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

public class ResponseInformationTest {

    @Test
    public void testName() {
        ResponseInformation ri = new ResponseInformation();
        assertEquals(ri.name(), "Response Information");
    }

    @Test
    public void testIdentifier() {
        ResponseInformation ri = new ResponseInformation();
        assertEquals(ri.identifier(), PropertyType.RESPONSE_INFORMATION.getType());
    }

    @Test
    public void testValue() {
        ResponseInformation ri = new ResponseInformation();
        assertNull(ri.value());
        String value = randomStr(5 + new Random().nextInt(50));
        ri.value(value);
        assertEquals(ri.value(), value);
    }
}
