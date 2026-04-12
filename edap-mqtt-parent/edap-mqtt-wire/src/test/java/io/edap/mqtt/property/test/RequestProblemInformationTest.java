package io.edap.mqtt.property.test;

import io.edap.mqtt.PropertyType;
import io.edap.mqtt.property.RequestProblemInformation;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

public class RequestProblemInformationTest {

    @Test
    public void testName() {
        RequestProblemInformation rpi = new RequestProblemInformation();
        assertEquals(rpi.name(), "Request Problem Information");
    }

    @Test
    public void testIdentifier() {
        RequestProblemInformation rpi = new RequestProblemInformation();
        assertEquals(rpi.identifier(), PropertyType.REQUEST_PROBLEM_INFORMATION.getType());
    }

    @Test
    public void testValue() {
        RequestProblemInformation rpi = new RequestProblemInformation();
        assertNotNull(rpi.value());
        assertEquals(rpi.value().byteValue(), 0);
        Byte value = (byte)new Random().nextInt(Byte.MAX_VALUE);
        rpi.value(value);
        assertEquals(rpi.value().byteValue(), value.byteValue());
    }
}
