package io.edap.mqtt.packet.test;

import io.edap.mqtt.ControlPacketType;
import io.edap.mqtt.packet.PingResp;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class PingRespTest {

    @Test
    public void testConstructor() {
        PingResp pingReq = new PingResp(55);
        assertEquals(pingReq.getType(), ControlPacketType.PINGRESP);
    }
}
