package io.edap.mqtt.property.test;

import io.edap.mqtt.PropertyType;
import io.edap.mqtt.property.ContentType;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static io.edap.mqtt.packet.test.ConnectTest.randomStr;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class ContentTypeTest {

    @Test
    public void testName() {
        ContentType ct = new ContentType();
        assertEquals(ct.name(), "Content Type");
    }

    @Test
    public void testIdentifier() {
        ContentType ct = new ContentType();
        assertEquals(ct.identifier(), PropertyType.CONTENT_TYPE.getType());
    }

    @Test
    public void testValue() {
        ContentType ct = new ContentType();
        String contentType = randomStr(10 + new Random().nextInt(20));
        ct.value(contentType);
        assertEquals(ct.value(), contentType);
    }
}
