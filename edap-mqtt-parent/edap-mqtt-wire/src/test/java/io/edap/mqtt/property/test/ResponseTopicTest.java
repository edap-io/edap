package io.edap.mqtt.property.test;

import io.edap.mqtt.PropertyType;
import io.edap.mqtt.property.ResponseTopic;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static io.edap.mqtt.packet.test.ConnectTest.randomStr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

public class ResponseTopicTest {

    @Test
    public void testName() {
        ResponseTopic rt = new ResponseTopic();
        assertEquals(rt.name(), "Response Topic");
    }

    @Test
    public void testIdentifier() {
        ResponseTopic rt = new ResponseTopic();
        assertEquals(rt.identifier(), PropertyType.RESPONSE_TOPIC.getType());
    }

    @Test
    public void testValue() {
        ResponseTopic rt = new ResponseTopic();
        assertNull(rt.value());
        String value = randomStr(5 + new Random().nextInt(100));
        rt.value(value);
        assertEquals(rt.value(), value);
    }
}
