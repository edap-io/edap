package io.edap.mqtt.test;

import io.edap.mqtt.MqttWriter;
import io.edap.mqtt.encoder.V31Encoder;
import io.edap.mqtt.packet.PingReq;
import io.edap.mqtt.packet.PingResp;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;

import static io.edap.mqtt.ControlPacketType.PINGREQ_VALUE;
import static io.edap.mqtt.ControlPacketType.PINGRESP_VALUE;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;

public class MqttEncoderPingTest {

    @Test
    public void testEncodePingReq() {
        V31Encoder v31Encoder = new V31Encoder();
        int fixedByteValue = PINGREQ_VALUE << 4;
        PingReq pingReq = new PingReq(fixedByteValue);
        MqttWriter writer = new MqttWriter();
        v31Encoder.encode(writer, pingReq);
        byte[] data = new byte[writer.getLength()];
        System.arraycopy(writer.getData(), writer.getStart(), data, 0, data.length);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(fixedByteValue);
        out.write(0);
        byte[] expect = out.toByteArray();
        assertArrayEquals(expect, data);
    }

    @Test
    public void testEncodePingResp() {
        V31Encoder v31Encoder = new V31Encoder();
        int fixedByteValue = PINGRESP_VALUE << 4;
        PingResp pingResp = new PingResp(fixedByteValue);
        MqttWriter writer = new MqttWriter();
        v31Encoder.encode(writer, pingResp);
        byte[] data = new byte[writer.getLength()];
        System.arraycopy(writer.getData(), writer.getStart(), data, 0, data.length);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(fixedByteValue);
        out.write(0);
        byte[] expect = out.toByteArray();
        assertArrayEquals(expect, data);
    }
}
