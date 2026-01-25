package io.edap.mqtt.test.wire.test;

import io.edap.mqtt.wire.ControlPacketType;
import io.edap.mqtt.wire.PubAck;
import io.edap.mqtt.wire.Publish;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static io.edap.mqtt.test.wire.test.ConnectTest.randomStr;
import static org.junit.jupiter.api.Assertions.assertEquals;

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
    public void testMessage() {
        Publish publish = new Publish(58);
        String message = randomStr(new Random().nextInt(50));
        publish.setMessage(message);
        assertEquals(publish.getMessage(), message);
    }
}
