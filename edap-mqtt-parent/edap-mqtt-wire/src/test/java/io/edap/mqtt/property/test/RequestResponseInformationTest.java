package io.edap.mqtt.property.test;

import io.edap.mqtt.PropertyType;
import io.edap.mqtt.property.RequestResponseInformation;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

public class RequestResponseInformationTest {

    @Test
    public void testName() {
        RequestResponseInformation rri = new RequestResponseInformation();
        assertEquals(rri.name(), "Request Response Information");
    }

    @Test
    public void testIdentifier() {
        RequestResponseInformation rri = new RequestResponseInformation();
        assertEquals(rri.identifier(), PropertyType.REQUEST_RESPONSE_INFORMATION.getType());
    }

    @Test
    public void testValue() {
        RequestResponseInformation rri = new RequestResponseInformation();
        assertNotNull(rri.value());
        assertEquals(rri.value().byteValue(), 0);
        Byte value = (byte)new Random().nextInt(Byte.MAX_VALUE);
        rri.value(value);
        assertEquals(rri.value().byteValue(), value.byteValue());
    }
}
