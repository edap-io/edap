package io.edap.mqtt.test.wire.test;

import io.edap.mqtt.wire.ControlPacketType;
import io.edap.mqtt.wire.PingResp;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class PingRespTest {

    @Test
    public void testConstructor() {
        PingResp pingReq = new PingResp(55);
        assertEquals(pingReq.getType(), ControlPacketType.PINGRESP);
    }
}
