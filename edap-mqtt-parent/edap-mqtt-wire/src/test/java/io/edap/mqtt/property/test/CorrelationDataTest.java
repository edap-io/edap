package io.edap.mqtt.property.test;

import io.edap.mqtt.PropertyType;
import io.edap.mqtt.property.CorrelationData;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Random;

import static io.edap.mqtt.packet.test.ConnectTest.randomStr;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class CorrelationDataTest {

    @Test
    public void testName() {
        CorrelationData cd = new CorrelationData();
        assertEquals(cd.name(), "Correlation Data");
    }

    @Test
    public void testIdentifier() {
        CorrelationData cd = new CorrelationData();
        assertEquals(cd.identifier(), PropertyType.CORRELATION_DATA.getType());
    }

    @Test
    public void testValue() {
        CorrelationData cd = new CorrelationData();
        byte[] data = randomStr(50 + new Random().nextInt(300)).getBytes(StandardCharsets.UTF_8);
        cd.value(data);
        assertArrayEquals(cd.value(), data);
    }
}
