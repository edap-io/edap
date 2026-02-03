package io.edap.mqtt.packet.test;

import io.edap.mqtt.ControlPacketType;
import io.edap.mqtt.PacketProperty;
import io.edap.mqtt.PropertyType;
import io.edap.mqtt.packet.Publish;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Random;

import static io.edap.mqtt.packet.test.ConnectTest.randomStr;
import static org.junit.jupiter.api.Assertions.*;

public class PublishTest {

    @Test
    public void testConstructor() {
        Publish publish = new Publish(58);
        assertEquals(publish.getType(), ControlPacketType.PUBLISH);
    }

    @Test
    public void testTopic() {
        Publish publish = new Publish(58);
        String topic = randomStr(new Random().nextInt(50));
        publish.setTopic(topic);
        assertEquals(publish.getTopic(), topic);
    }

    @Test
    public void testPacketIdentifier() {
        Publish publish = new Publish(58);
        int packetIdentifier = new Random().nextInt();
        publish.setPacketIdentifier(packetIdentifier);
        assertEquals(publish.getPacketIdentifier(), packetIdentifier);
    }

    @Test
    public void testPayload() {
        Publish publish = new Publish(58);
        byte[] payload = randomStr(new Random().nextInt(500)).getBytes(StandardCharsets.UTF_8);
        publish.setPayload(payload);
        assertArrayEquals(publish.getPayload(), payload);
    }

    @Test
    public void testProperties() {
        Publish publish = new Publish(58);
        LinkedHashMap<PropertyType, PacketProperty> props = new LinkedHashMap<>();
        assertNull(publish.getProperties());
        publish.setProperties(props);
        assertEquals(publish.getProperties().size(), 0);

    }
}
