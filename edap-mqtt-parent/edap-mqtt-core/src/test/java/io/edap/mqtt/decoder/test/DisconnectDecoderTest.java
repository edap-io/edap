package io.edap.mqtt.decoder.test;

import io.edap.buffer.FastBuf;
import io.edap.mqtt.ControlPacket;
import io.edap.mqtt.ControlPacketType;
import io.edap.mqtt.ParseContext;
import io.edap.mqtt.PropertyType;
import io.edap.mqtt.decoder.DisconnectDecoder;
import io.edap.mqtt.packet.Disconnect;
import io.edap.mqtt.property.ReasonString;
import io.edap.mqtt.property.ServerReference;
import io.edap.mqtt.test.MqttNioSessionV311;
import io.edap.mqtt.test.MqttNioSessionV5;
import io.edap.nio.ParseResult;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Random;

import static io.edap.mqtt.test.TestUtil.randomStr;
import static io.edap.mqtt.test.TestUtil.writeMqttVarInt;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class DisconnectDecoderTest {

    @Test
    public void testParse() {
        DisconnectDecoder decoder = new DisconnectDecoder();
        ParseResult<ControlPacket> r;
        FastBuf buf = new FastBuf(4096);
        int fixHeader = (ControlPacketType.DISCONNECT_VALUE << 4);

        int len = 127;
        buf.write((byte) fixHeader);
        writeMqttVarInt(buf, len);
        ParseContext parseCtx = new ParseContext();
        parseCtx.setSession(new MqttNioSessionV311());
        parseCtx.setRpos(buf.rpos());
        parseCtx.setParseData(new byte[4]);
        parseCtx.setRpos(buf.rpos() + 2);
        r = decoder.parse(buf, fixHeader, parseCtx);
        assertEquals(r.isFinished(), false);

        len = 2097152;
        buf.write((byte) fixHeader);
        writeMqttVarInt(buf, len);
        parseCtx = new ParseContext();
        parseCtx.setSession(new MqttNioSessionV311());
        parseCtx.setRpos(buf.rpos());
        parseCtx.setParseData(new byte[4]);
        parseCtx.setRpos(buf.rpos() + 2);
        buf.wpos(buf.wpos() - 2);
        r = decoder.parse(buf, fixHeader, parseCtx);
        assertEquals(r.isFinished(), false);

        len = 0;
        buf.reset();
        buf.write((byte) fixHeader);
        writeMqttVarInt(buf, len);
        parseCtx = new ParseContext();
        parseCtx.setSession(new MqttNioSessionV311());
        parseCtx.setRpos(buf.rpos());
        parseCtx.setParseData(new byte[4]);
        parseCtx.setRpos(buf.rpos() + 1);
        r = decoder.parse(buf, fixHeader, parseCtx);
        assertEquals(r.isFinished(), true);

        len = 2;
        int reasonCode = 135;
        buf.reset();
        buf.write((byte) fixHeader);
        writeMqttVarInt(buf, len);
        buf.write((byte)(135 & 0xFF));
        buf.write((byte)0);
        parseCtx = new ParseContext();
        parseCtx.setSession(new MqttNioSessionV311());
        parseCtx.setRpos(buf.rpos());
        parseCtx.setParseData(new byte[4]);
        parseCtx.setRpos(buf.rpos() + 1);
        r = decoder.parse(buf, fixHeader, parseCtx);
        assertEquals(r.isFinished(), true);

        String reason = randomStr(30 + new Random().nextInt(5));
        String key = randomStr(2 + new Random().nextInt(10));
        String val = randomStr(3 + new Random().nextInt(40));
        String serverReference = randomStr(15 + new Random().nextInt(20));
        int propLen = 3 + reason.getBytes(StandardCharsets.UTF_8).length +
                3 + key.getBytes(StandardCharsets.UTF_8).length +
                2 + val.getBytes(StandardCharsets.UTF_8).length +
                3 + serverReference.getBytes(StandardCharsets.UTF_8).length;
        len = 1 + propLen;
        buf.reset();
        buf.write((byte) fixHeader);
        writeMqttVarInt(buf, len);
        buf.write((byte)(135));
        writeMqttVarInt(buf, propLen);
        buf.write((byte) 31);
        int reasonLen = reason.getBytes(StandardCharsets.UTF_8).length;
        buf.write((byte)((reasonLen >> 8) & 0xFF));
        buf.write((byte)(reasonLen));
        buf.write(reason.getBytes(StandardCharsets.UTF_8));
        buf.write((byte) 38);
        int keyLen = key.getBytes(StandardCharsets.UTF_8).length;
        buf.write((byte)((keyLen >> 8) & 0xFF));
        buf.write((byte)(keyLen & 0xFF));
        buf.write(key.getBytes(StandardCharsets.UTF_8));
        int valLen = val.getBytes(StandardCharsets.UTF_8).length;
        buf.write((byte)((valLen >> 8) & 0xFF));
        buf.write((byte)(valLen & 0xFF));
        buf.write(val.getBytes(StandardCharsets.UTF_8));
        buf.write((byte) 28);
        int serverReferenceLen = serverReference.getBytes(StandardCharsets.UTF_8).length;
        buf.write((byte)((serverReferenceLen >> 8) & 0xFF));
        buf.write((byte)(serverReferenceLen & 0xFF));
        buf.write(serverReference.getBytes(StandardCharsets.UTF_8));


        parseCtx = new ParseContext();
        parseCtx.setSession(new MqttNioSessionV5());
        parseCtx.setRpos(buf.rpos());
        parseCtx.setParseData(new byte[4]);
        parseCtx.setRpos(buf.rpos() + 1);
        r = decoder.parse(buf, fixHeader, parseCtx);
        assertEquals(r.isFinished(), true);
        Disconnect disconnect = (Disconnect)r.getMessage();
        assertEquals(disconnect.getReasonCode(), reasonCode);
        assertEquals(disconnect.getProperties().size(), 3);
        assertEquals(disconnect.getProperties().get(PropertyType.REASON_STRING) instanceof ReasonString, true);
        ReasonString reasonString = (ReasonString)disconnect.getProperties().get(PropertyType.REASON_STRING);
        assertEquals(reasonString.value(), reason);
        assertEquals(disconnect.getProperties().get(PropertyType.SERVER_REFERENCE) instanceof ServerReference, true);
        ServerReference sr = (ServerReference)disconnect.getProperties().get(PropertyType.SERVER_REFERENCE);
        assertEquals(sr.value(), serverReference);

        assertEquals(buf.wpos(), buf.rpos());
    }
}
