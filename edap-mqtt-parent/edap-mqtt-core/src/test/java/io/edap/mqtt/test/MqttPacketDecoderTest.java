package io.edap.mqtt.test;

import io.edap.buffer.FastBuf;
import io.edap.mqtt.PacketProperty;
import io.edap.mqtt.ParseContext;
import io.edap.mqtt.PropertyType;
import io.edap.mqtt.decoder.ConnAckDecoder;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Random;

import static io.edap.mqtt.test.TestUtil.randomStr;
import static io.edap.mqtt.test.TestUtil.writeMqttVarInt;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class MqttPacketDecoderTest {

    @Test
    public void parseProperties() {
        ConnAckDecoder decoder = new ConnAckDecoder();
        FastBuf buf = new FastBuf(4096);
        String contentType = randomStr(25);
        int len = 3 + contentType.getBytes(StandardCharsets.UTF_8).length;
        writeMqttVarInt(buf, len);
        buf.write((byte)3);
        int ctlen = contentType.length();
        buf.write((byte)((ctlen >> 8) & 0xFF));
        buf.write((byte)(ctlen & 0xFF));
        buf.write(contentType.getBytes(StandardCharsets.UTF_8));

        ParseContext parseCtx = new ParseContext();
        parseCtx.setSession(new MqttNioSessionV5());
        parseCtx.setParseData(new byte[4]);
        parseCtx.setRpos(buf.rpos());
        LinkedHashMap<PropertyType, PacketProperty> props = decoder.parseProperties(buf, parseCtx);
        assertEquals(props.size(), 1);
        assertEquals(props.get(PropertyType.CONTENT_TYPE).value(), contentType);

        String authMethod = randomStr(5 + new Random().nextInt(10));
        len = 3 + authMethod.getBytes(StandardCharsets.UTF_8).length;
        buf.reset();
        writeMqttVarInt(buf, len);
        buf.write((byte)21);
        int vlen = authMethod.length();
        buf.write((byte)((vlen >> 8) & 0xFF));
        buf.write((byte)(vlen & 0xFF));
        buf.write(authMethod.getBytes(StandardCharsets.UTF_8));

        parseCtx = new ParseContext();
        parseCtx.setSession(new MqttNioSessionV5());
        parseCtx.setParseData(new byte[4]);
        parseCtx.setRpos(buf.rpos());
        props = decoder.parseProperties(buf, parseCtx);
        assertEquals(props.size(), 1);
        assertEquals(props.get(PropertyType.AUTHENTICATION_METHOD).value(), authMethod);

        String serverReference = randomStr(15 + new Random().nextInt(10));
        len = 3 + serverReference.getBytes(StandardCharsets.UTF_8).length;
        buf.reset();
        writeMqttVarInt(buf, len);
        buf.write((byte)28);
        vlen = serverReference.length();
        buf.write((byte)((vlen >> 8) & 0xFF));
        buf.write((byte)(vlen & 0xFF));
        buf.write(serverReference.getBytes(StandardCharsets.UTF_8));

        parseCtx = new ParseContext();
        parseCtx.setSession(new MqttNioSessionV5());
        parseCtx.setParseData(new byte[4]);
        parseCtx.setRpos(buf.rpos());
        props = decoder.parseProperties(buf, parseCtx);
        assertEquals(props.size(), 1);
        assertEquals(props.get(PropertyType.SERVER_REFERENCE).value(), serverReference);

        String respInfo = randomStr(15 + new Random().nextInt(10));
        len = 3 + respInfo.getBytes(StandardCharsets.UTF_8).length;
        buf.reset();
        writeMqttVarInt(buf, len);
        buf.write((byte)26);
        vlen = respInfo.length();
        buf.write((byte)((vlen >> 8) & 0xFF));
        buf.write((byte)(vlen & 0xFF));
        buf.write(respInfo.getBytes(StandardCharsets.UTF_8));

        parseCtx = new ParseContext();
        parseCtx.setSession(new MqttNioSessionV5());
        parseCtx.setParseData(new byte[4]);
        parseCtx.setRpos(buf.rpos());
        props = decoder.parseProperties(buf, parseCtx);
        assertEquals(props.size(), 1);
        assertEquals(props.get(PropertyType.RESPONSE_INFORMATION).value(), respInfo);

        Random random = new Random();
        int subIdentifier = random.nextInt(Byte.MAX_VALUE);
        int varLen;
        if (subIdentifier > 2097151) {
            varLen = 4;
        } else if (subIdentifier > 16383) {
            varLen = 3;
        } else if (subIdentifier > 127) {
            varLen = 2;
        } else {
            varLen = 1;
        }
        len = 1 + varLen;
        buf.reset();
        writeMqttVarInt(buf, len);
        buf.write((byte)11);
        writeMqttVarInt(buf, subIdentifier);

        parseCtx = new ParseContext();
        parseCtx.setSession(new MqttNioSessionV5());
        parseCtx.setParseData(new byte[4]);
        parseCtx.setRpos(buf.rpos());
        props = decoder.parseProperties(buf, parseCtx);
        assertEquals(props.size(), 1);
        assertEquals(props.get(PropertyType.SUBSCRIPTION_INDENTIFIER).value(), subIdentifier);

        subIdentifier = 128;
        if (subIdentifier > 2097151) {
            varLen = 4;
        } else if (subIdentifier > 16383) {
            varLen = 3;
        } else if (subIdentifier > 127) {
            varLen = 2;
        } else {
            varLen = 1;
        }
        len = 1 + varLen;
        buf.reset();
        writeMqttVarInt(buf, len);
        buf.write((byte)11);
        writeMqttVarInt(buf, subIdentifier);

        parseCtx = new ParseContext();
        parseCtx.setSession(new MqttNioSessionV5());
        parseCtx.setParseData(new byte[4]);
        parseCtx.setRpos(buf.rpos());
        props = decoder.parseProperties(buf, parseCtx);
        assertEquals(props.size(), 1);
        assertEquals(props.get(PropertyType.SUBSCRIPTION_INDENTIFIER).value(), subIdentifier);

        subIdentifier = 16384;
        if (subIdentifier > 2097151) {
            varLen = 4;
        } else if (subIdentifier > 16383) {
            varLen = 3;
        } else if (subIdentifier > 127) {
            varLen = 2;
        } else {
            varLen = 1;
        }
        len = 1 + varLen;
        buf.reset();
        writeMqttVarInt(buf, len);
        buf.write((byte)11);
        writeMqttVarInt(buf, subIdentifier);

        parseCtx = new ParseContext();
        parseCtx.setSession(new MqttNioSessionV5());
        parseCtx.setParseData(new byte[4]);
        parseCtx.setRpos(buf.rpos());
        props = decoder.parseProperties(buf, parseCtx);
        assertEquals(props.size(), 1);
        assertEquals(props.get(PropertyType.SUBSCRIPTION_INDENTIFIER).value(), subIdentifier);
    }
}
