package io.edap.mqtt.test.wire.test;

import io.edap.mqtt.wire.ConnAck;
import io.edap.mqtt.wire.ControlPacketType;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class ConnAckTest {

    @Test
    public void testConstructor() {
        ConnAck connAck = new ConnAck(51);
        assertEquals(connAck.getType(), ControlPacketType.CONNACK);
    }

    @Test
    public void testConnAckFlag() {
        ConnAck connAck = new ConnAck(51);
        int ackFlag = new Random().nextInt();
        connAck.setConnAckFlag(ackFlag);
        assertEquals(connAck.getConnAckFlag(), ackFlag);
    }

    @Test
    public void testconnAckCode() {
        ConnAck connAck = new ConnAck(51);
        int ackCode = new Random().nextInt();
        connAck.setConnAckCode(ackCode);
        assertEquals(connAck.getConnAckCode(), ackCode);
    }
}
