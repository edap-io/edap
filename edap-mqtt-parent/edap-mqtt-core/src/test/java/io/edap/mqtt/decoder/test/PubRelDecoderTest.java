package io.edap.mqtt.decoder.test;

import io.edap.buffer.FastBuf;
import io.edap.mqtt.*;
import io.edap.mqtt.decoder.PubRelDecoder;
import io.edap.mqtt.packet.PubRel;
import io.edap.mqtt.property.StringPair;
import io.edap.mqtt.property.UserProperty;
import io.edap.mqtt.test.MqttNioSessionV311;
import io.edap.mqtt.test.MqttNioSessionV5;
import io.edap.nio.ParseResult;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Random;

import static io.edap.mqtt.test.TestUtil.randomStr;
import static io.edap.mqtt.test.TestUtil.writeMqttVarInt;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class PubRelDecoderTest {

    @Test
    public void testParse() {
        PubRelDecoder decoder = new PubRelDecoder();
        ParseResult<ControlPacket> r;
        FastBuf buf = new FastBuf(4096);
        int fixHeader = (ControlPacketType.PUBREL_VALUE << 4);

        int len = 127;
        buf.write((byte) fixHeader);
        writeMqttVarInt(buf, len);
        ParseContext parseCtx = new ParseContext();
        parseCtx.setSession(new MqttNioSessionV311());
        parseCtx.setRpos(buf.rpos());
        parseCtx.setParseData(new byte[4]);
        parseCtx.setRpos(buf.rpos() + 2);
        parseCtx.setResult(new ParseResult<>());
        r = decoder.parse(buf, fixHeader, parseCtx);
        assertEquals(r.isFinished(), false);

        len = 2097152;
        buf.reset();
        buf.write((byte) fixHeader);
        writeMqttVarInt(buf, len);
        int packetIdentifier = 100 + new Random().nextInt(Short.MAX_VALUE - 100);
        buf.write((byte)(packetIdentifier >> 8 & 0xFF));
        buf.write((byte)(packetIdentifier & 0xFF));
        parseCtx = new ParseContext();
        parseCtx.setSession(new MqttNioSessionV311());
        parseCtx.setRpos(buf.rpos());
        parseCtx.setParseData(new byte[4]);
        parseCtx.setRpos(buf.rpos() + 1);
        buf.wpos(buf.wpos()-3);
        parseCtx.setResult(new ParseResult<>());
        r = decoder.parse(buf, fixHeader, parseCtx);
        assertEquals(r.isFinished(), false);

        len = 2;
        buf.reset();
        buf.write((byte) fixHeader);
        writeMqttVarInt(buf, len);
        packetIdentifier = 100 + new Random().nextInt(Short.MAX_VALUE - 100);
        buf.write((byte)(packetIdentifier >> 8 & 0xFF));
        buf.write((byte)(packetIdentifier & 0xFF));
        parseCtx = new ParseContext();
        parseCtx.setSession(new MqttNioSessionV311());
        parseCtx.setRpos(buf.rpos());
        parseCtx.setParseData(new byte[4]);
        parseCtx.setRpos(buf.rpos() + 1);
        parseCtx.setResult(new ParseResult<>());
        buf.wpos(buf.wpos() - 1);
        r = decoder.parse(buf, fixHeader, parseCtx);
        assertEquals(r.isFinished(), false);

        len = 2;
        buf.reset();
        buf.write((byte) fixHeader);
        writeMqttVarInt(buf, len);
        packetIdentifier = 100 + new Random().nextInt(Short.MAX_VALUE - 100);
        buf.write((byte)(packetIdentifier >> 8 & 0xFF));
        buf.write((byte)(packetIdentifier & 0xFF));
        parseCtx = new ParseContext();
        parseCtx.setSession(new MqttNioSessionV311());
        parseCtx.setRpos(buf.rpos());
        parseCtx.setParseData(new byte[4]);
        parseCtx.setRpos(buf.rpos() + 1);
        parseCtx.setResult(new ParseResult<>());
        r = decoder.parse(buf, fixHeader, parseCtx);
        assertEquals(r.isFinished(), true);
        assertEquals(r.getMessage() instanceof PubRel, true);
        PubRel pubRel = (PubRel)r.getMessage();
        assertEquals(pubRel.getPacketIdentifier(), packetIdentifier);
        assertEquals(buf.wpos(), parseCtx.getRpos());

        String reason = randomStr(30 + new Random().nextInt(60));
        String key1 = randomStr(5 + new Random().nextInt(10));
        String val1 = randomStr(10 + new Random().nextInt(20));

        String key2 = randomStr(25 + new Random().nextInt(10));
        String val2 = randomStr(30 + new Random().nextInt(20));

        int reasonCode = new Random().nextInt(127 + Byte.MAX_VALUE);
        int propLen = 3 + reason.getBytes(StandardCharsets.UTF_8).length +
                3 + key1.getBytes(StandardCharsets.UTF_8).length +
                2 + val1.getBytes(StandardCharsets.UTF_8).length +
                3 + key2.getBytes(StandardCharsets.UTF_8).length +
                2 + val2.getBytes(StandardCharsets.UTF_8).length;
        len = 2 + 1 + propLen;

        buf.reset();
        buf.write((byte) fixHeader);
        writeMqttVarInt(buf, len);
        packetIdentifier = 100 + new Random().nextInt(Short.MAX_VALUE - 100);
        buf.write((byte)(packetIdentifier >> 8 & 0xFF));
        buf.write((byte)(packetIdentifier & 0xFF));
        buf.write((byte)(reasonCode));
        buf.write((byte)31);
        int reasonLen = reason.getBytes(StandardCharsets.UTF_8).length;
        buf.write((byte)(reasonLen >> 8 & 0xFF));
        buf.write((byte)(reasonLen & 0xFF));
        buf.write(reason.getBytes(StandardCharsets.UTF_8));
        buf.write((byte)38);
        int key1Len = key1.getBytes(StandardCharsets.UTF_8).length;
        buf.write((byte)(key1Len >> 8 & 0xFF));
        buf.write((byte)(key1Len & 0xFF));
        buf.write(key1.getBytes(StandardCharsets.UTF_8));
        int val1Len = val1.getBytes(StandardCharsets.UTF_8).length;
        buf.write((byte)(val1Len >> 8 & 0xFF));
        buf.write((byte)(val1Len & 0xFF));
        buf.write(val1.getBytes(StandardCharsets.UTF_8));

        buf.write((byte)38);
        int key2Len = key2.getBytes(StandardCharsets.UTF_8).length;
        buf.write((byte)(key2Len >> 8 & 0xFF));
        buf.write((byte)(key2Len & 0xFF));
        buf.write(key2.getBytes(StandardCharsets.UTF_8));
        int val2Len = val2.getBytes(StandardCharsets.UTF_8).length;
        buf.write((byte)(val2Len >> 8 & 0xFF));
        buf.write((byte)(val2Len & 0xFF));
        buf.write(val2.getBytes(StandardCharsets.UTF_8));


        parseCtx = new ParseContext();
        parseCtx.setSession(new MqttNioSessionV311());
        parseCtx.setRpos(buf.rpos());
        parseCtx.setParseData(new byte[4]);
        parseCtx.setRpos(buf.rpos() + 1);
        parseCtx.setResult(new ParseResult<>());
        r = decoder.parse(buf, fixHeader, parseCtx);
        assertEquals(r.isFinished(), true);
        assertEquals(buf.wpos(), parseCtx.getRpos());

        buf.reset();
        buf.write((byte) fixHeader);
        writeMqttVarInt(buf, len);
        packetIdentifier = 100 + new Random().nextInt(Short.MAX_VALUE - 100);
        buf.write((byte)(packetIdentifier >> 8 & 0xFF));
        buf.write((byte)(packetIdentifier & 0xFF));
        buf.write((byte)(reasonCode));
        writeMqttVarInt(buf, propLen);
        buf.write((byte)31);
        reasonLen = reason.getBytes(StandardCharsets.UTF_8).length;
        buf.write((byte)((reasonLen >> 8) & 0xFF));
        buf.write((byte)(reasonLen & 0xFF));
        buf.write(reason.getBytes(StandardCharsets.UTF_8));
        buf.write((byte)38);
        key1Len = key1.getBytes(StandardCharsets.UTF_8).length;
        buf.write((byte)(key1Len >> 8 & 0xFF));
        buf.write((byte)(key1Len & 0xFF));
        buf.write(key1.getBytes(StandardCharsets.UTF_8));
        val1Len = val1.getBytes(StandardCharsets.UTF_8).length;
        buf.write((byte)(val1Len >> 8 & 0xFF));
        buf.write((byte)(val1Len & 0xFF));
        buf.write(val1.getBytes(StandardCharsets.UTF_8));

        buf.write((byte)38);
        key2Len = key2.getBytes(StandardCharsets.UTF_8).length;
        buf.write((byte)(key2Len >> 8 & 0xFF));
        buf.write((byte)(key2Len & 0xFF));
        buf.write(key2.getBytes(StandardCharsets.UTF_8));
        val2Len = val2.getBytes(StandardCharsets.UTF_8).length;
        buf.write((byte)(val2Len >> 8 & 0xFF));
        buf.write((byte)(val2Len & 0xFF));
        buf.write(val2.getBytes(StandardCharsets.UTF_8));


        parseCtx = new ParseContext();
        parseCtx.setSession(new MqttNioSessionV5());
        parseCtx.setRpos(buf.rpos());
        parseCtx.setParseData(new byte[4]);
        parseCtx.setRpos(buf.rpos() + 1);
        parseCtx.setResult(new ParseResult<>());
        r = decoder.parse(buf, fixHeader, parseCtx);
        assertEquals(r.isFinished(), true);
        assertEquals(r.getMessage() instanceof PubRel, true);
        pubRel = (PubRel)r.getMessage();
        assertEquals(pubRel.getPacketIdentifier(), packetIdentifier);
        assertEquals(pubRel.getReasonCode(), reasonCode);
        LinkedHashMap<PropertyType, PacketProperty> props = pubRel.getProperties();
        assertEquals(props.size(), 2);
        assertEquals(props.get(PropertyType.REASON_STRING).value(), reason);
        assertEquals(props.get(PropertyType.USER_PROPERTY) instanceof UserProperty, true);
        UserProperty up = (UserProperty) props.get(PropertyType.USER_PROPERTY);
        List<StringPair> pairs = up.value();
        assertEquals(pairs.size(), 2);
        assertEquals(pairs.get(0).getName(), key1);
        assertEquals(pairs.get(0).getValue(), val1);
        assertEquals(pairs.get(1).getName(), key2);
        assertEquals(pairs.get(1).getValue(), val2);
        assertEquals(buf.wpos(), parseCtx.getRpos());
        assertEquals(buf.wpos(), buf.rpos());
    }
}
