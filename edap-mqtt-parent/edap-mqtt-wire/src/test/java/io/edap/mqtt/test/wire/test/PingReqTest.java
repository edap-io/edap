package io.edap.mqtt.test.wire.test;

import io.edap.mqtt.wire.ControlPacketType;
import io.edap.mqtt.wire.PingReq;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class PingReqTest {

    @Test
    public void testConstructor() {
        PingReq pingReq = new PingReq(54);
        assertEquals(pingReq.getType(), ControlPacketType.PINGREQ);
    }
}
