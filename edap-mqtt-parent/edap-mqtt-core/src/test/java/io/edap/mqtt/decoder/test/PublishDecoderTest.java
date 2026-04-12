package io.edap.mqtt.decoder.test;

import io.edap.buffer.FastBuf;
import io.edap.mqtt.*;
import io.edap.mqtt.decoder.PublishDecoder;
import io.edap.mqtt.decoder.SubscribeDecoder;
import io.edap.mqtt.packet.Publish;
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
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class PublishDecoderTest {

    @Test
    public void testParse() {
        PublishDecoder decoder = new PublishDecoder();
        ParseResult<ControlPacket> r;
        FastBuf buf = new FastBuf(4096000);
        int fixHeader = (ControlPacketType.PUBLISH_VALUE << 4);

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
        buf.write((byte) (packetIdentifier >> 8 & 0xFF));
        buf.write((byte) (packetIdentifier & 0xFF));
        parseCtx = new ParseContext();
        parseCtx.setSession(new MqttNioSessionV311());
        parseCtx.setRpos(buf.rpos());
        parseCtx.setParseData(new byte[4]);
        parseCtx.setRpos(buf.rpos() + 1);
        buf.wpos(buf.wpos() - 3);
        parseCtx.setResult(new ParseResult<>());
        r = decoder.parse(buf, fixHeader, parseCtx);
        assertEquals(r.isFinished(), false);

        len = 2;
        buf.reset();
        buf.write((byte) fixHeader);
        writeMqttVarInt(buf, len);

        parseCtx = new ParseContext();
        parseCtx.setSession(new MqttNioSessionV311());
        parseCtx.setRpos(buf.rpos());
        parseCtx.setParseData(new byte[4]);
        parseCtx.setRpos(buf.rpos() + 1);
        parseCtx.setResult(new ParseResult<>());
        buf.wpos(buf.wpos() - 1);
        r = decoder.parse(buf, fixHeader, parseCtx);
        assertEquals(r.isFinished(), false);

        String topicName = randomStr(5 + 40);
        String payload = randomStr(100 + new Random().nextInt(200));
        len = 2 + topicName.getBytes(StandardCharsets.UTF_8).length;
        buf.reset();
        buf.write((byte) fixHeader);
        writeMqttVarInt(buf, len  + 10);
        int topicLen = topicName.getBytes(StandardCharsets.UTF_8).length;
        buf.write((byte)(topicLen >> 8 & 0xFF));
        buf.write((byte)(topicLen & 0xFF));
        buf.write(topicName.getBytes(StandardCharsets.UTF_8));
        parseCtx = new ParseContext();
        parseCtx.setSession(new MqttNioSessionV311());
        parseCtx.setRpos(buf.rpos());
        parseCtx.setParseData(new byte[4]);
        parseCtx.setRpos(buf.rpos() + 1);
        parseCtx.setResult(new ParseResult<>());
        buf.wpos(buf.wpos());
        r = decoder.parse(buf, fixHeader, parseCtx);
        assertEquals(r.isFinished(), false);

        packetIdentifier = Short.MAX_VALUE + new Random().nextInt(Short.MAX_VALUE);
        len = 2 + topicName.getBytes(StandardCharsets.UTF_8).length +
            payload.getBytes(StandardCharsets.UTF_8).length;

        buf.reset();
        buf.write((byte) fixHeader);
        writeMqttVarInt(buf, len);
        topicLen = topicName.getBytes(StandardCharsets.UTF_8).length;
        buf.write((byte)(topicLen >> 8 & 0xFF));
        buf.write((byte)(topicLen & 0xFF));
        buf.write(topicName.getBytes(StandardCharsets.UTF_8));
        buf.write(payload.getBytes(StandardCharsets.UTF_8));

        parseCtx = new ParseContext();
        parseCtx.setSession(new MqttNioSessionV311());
        parseCtx.setRpos(buf.rpos());
        parseCtx.setParseData(new byte[4]);
        parseCtx.setRpos(buf.rpos() + 1);
        parseCtx.setResult(new ParseResult<>());
        buf.wpos(buf.wpos());
        r = decoder.parse(buf, fixHeader, parseCtx);
        assertEquals(r.getMessage() instanceof Publish, true);
        Publish publish = (Publish) r.getMessage();
        assertEquals(publish.getDup(), 0);
        assertEquals(publish.getQos().getValue(), 0);
        assertEquals(publish.getRetain(), 0);
        assertArrayEquals(publish.getPayload(), payload.getBytes(StandardCharsets.UTF_8));

        buf.reset();
        buf.write((byte) (fixHeader | (1 << 3)));
        writeMqttVarInt(buf, len);
        buf.write((byte)(topicLen >> 8 & 0xFF));
        buf.write((byte)(topicLen & 0xFF));
        buf.write(topicName.getBytes(StandardCharsets.UTF_8));
        buf.write(payload.getBytes(StandardCharsets.UTF_8));

        parseCtx = new ParseContext();
        parseCtx.setSession(new MqttNioSessionV311());
        parseCtx.setRpos(buf.rpos());
        parseCtx.setParseData(new byte[4]);
        parseCtx.setRpos(buf.rpos() + 1);
        parseCtx.setResult(new ParseResult<>());
        buf.wpos(buf.wpos());
        r = decoder.parse(buf, fixHeader  | (1 << 3), parseCtx);
        assertEquals(r.getMessage() instanceof Publish, true);
        publish = (Publish) r.getMessage();
        assertEquals(publish.getDup(), 1);
        assertEquals(publish.getQos(), QoSLevel.MOST_ONCE);
        assertEquals(publish.getRetain(), 0);
        assertArrayEquals(publish.getPayload(), payload.getBytes(StandardCharsets.UTF_8));

        len = 2 + topicName.getBytes(StandardCharsets.UTF_8).length + 2 +
                payload.getBytes(StandardCharsets.UTF_8).length;
        buf.reset();
        buf.write((byte) (fixHeader | (1 << 3)));
        writeMqttVarInt(buf, len);
        buf.write((byte)(topicLen >> 8 & 0xFF));
        buf.write((byte)(topicLen & 0xFF));
        buf.write(topicName.getBytes(StandardCharsets.UTF_8));
        buf.write((byte)(packetIdentifier >> 8 & 0xFF));
        buf.write((byte)(packetIdentifier & 0xFF));
        buf.write(payload.getBytes(StandardCharsets.UTF_8));
        parseCtx = new ParseContext();
        parseCtx.setSession(new MqttNioSessionV311());
        parseCtx.setRpos(buf.rpos());
        parseCtx.setParseData(new byte[4]);
        parseCtx.setRpos(buf.rpos() + 1);
        parseCtx.setResult(new ParseResult<>());
        buf.wpos(buf.wpos());
        r = decoder.parse(buf, fixHeader  | (1 << 3) | (1 << 2), parseCtx);
        assertEquals(r.getMessage() instanceof Publish, true);
        publish = (Publish) r.getMessage();
        assertEquals(publish.getDup(), 1);
        assertEquals(publish.getQos(), QoSLevel.EXACTLY_ONCE);
        assertEquals(publish.getRetain(), 0);
        assertArrayEquals(publish.getPayload(), payload.getBytes(StandardCharsets.UTF_8));

        buf.rpos(buf.address());
        parseCtx = new ParseContext();
        parseCtx.setSession(new MqttNioSessionV311());
        parseCtx.setRpos(buf.rpos());
        parseCtx.setParseData(new byte[4]);
        parseCtx.setRpos(buf.rpos() + 1);
        parseCtx.setResult(new ParseResult<>());
        buf.wpos(buf.wpos());
        r = decoder.parse(buf, fixHeader  | (1 << 3) | (1 << 1), parseCtx);
        assertEquals(r.getMessage() instanceof Publish, true);
        publish = (Publish) r.getMessage();
        assertEquals(publish.getDup(), 1);
        assertEquals(publish.getQos(), QoSLevel.LEAST_ONCE);
        assertEquals(publish.getRetain(), 0);
        assertArrayEquals(publish.getPayload(), payload.getBytes(StandardCharsets.UTF_8));

        buf.rpos(buf.address());
        parseCtx = new ParseContext();
        parseCtx.setSession(new MqttNioSessionV311());
        parseCtx.setRpos(buf.rpos());
        parseCtx.setParseData(new byte[4]);
        parseCtx.setRpos(buf.rpos() + 1);
        parseCtx.setResult(new ParseResult<>());
        buf.wpos(buf.wpos());
        r = decoder.parse(buf, fixHeader  | (1 << 3) | (1 << 1 | 1), parseCtx);
        assertEquals(r.getMessage() instanceof Publish, true);
        publish = (Publish) r.getMessage();
        assertEquals(publish.getDup(), 1);
        assertEquals(publish.getQos(), QoSLevel.LEAST_ONCE);
        assertEquals(publish.getRetain(), 1);
        assertArrayEquals(publish.getPayload(), payload.getBytes(StandardCharsets.UTF_8));

        Random random = new Random();
        int payloadFormatIndicator = random.nextInt(2);
        int messageExpiryInterval = Short.MAX_VALUE + random.nextInt(Integer.MAX_VALUE - Short.MAX_VALUE);
        int topicAlias = Short.MAX_VALUE + random.nextInt(Short.MAX_VALUE);
        String responseTopic = randomStr(5 + random.nextInt(Short.MAX_VALUE));
        byte[] correlationData = randomStr(10 + random.nextInt(50)).getBytes(StandardCharsets.UTF_8);
        String key1 = randomStr(5 + new Random().nextInt(10));
        String val1 = randomStr(10 + new Random().nextInt(20));
        int subscriptionIdentifier = Short.MAX_VALUE + random.nextInt((Integer.MAX_VALUE >> 3) - Short.MAX_VALUE);
        String contentType = randomStr(5 + random.nextInt(10));
        int varLen;
        if (subscriptionIdentifier > 2097151) {
            varLen = 4;
        } else if (subscriptionIdentifier > 16383) {
            varLen = 3;
        } else if (subscriptionIdentifier > 127) {
            varLen = 2;
        } else {
            varLen = 1;
        }
        int propLen = 2 + 5 + 3 +
                1 + responseTopic.getBytes(StandardCharsets.UTF_8).length +
                3 + correlationData.length +
                3 + key1.getBytes(StandardCharsets.UTF_8).length +
                2 + val1.getBytes(StandardCharsets.UTF_8).length +
                1 + varLen +
                3 + contentType.getBytes(StandardCharsets.UTF_8).length;

        if (propLen > 2097151) {
            varLen = 4;
        } else if (propLen > 16383) {
            varLen = 3;
        } else if (propLen > 127) {
            varLen = 2;
        } else {
            varLen = 1;
        }
        len = 2 + topicName.getBytes(StandardCharsets.UTF_8).length + 2 +
                payload.getBytes(StandardCharsets.UTF_8).length + propLen + varLen;
        buf.reset();
        buf.write((byte) (fixHeader | (1 << 3)));
        writeMqttVarInt(buf, len);
        buf.write((byte)(topicLen >> 8 & 0xFF));
        buf.write((byte)(topicLen & 0xFF));
        buf.write(topicName.getBytes(StandardCharsets.UTF_8));
        buf.write((byte)(packetIdentifier >> 8 & 0xFF));
        buf.write((byte)(packetIdentifier & 0xFF));
        writeMqttVarInt(buf, propLen);
        buf.write((byte)1);
        buf.write((byte)payloadFormatIndicator);
        buf.write((byte)2);
        buf.write((byte)((messageExpiryInterval >> 24) & 0xFF));
        buf.write((byte)((messageExpiryInterval >> 16) & 0xFF));
        buf.write((byte)((messageExpiryInterval >> 8) & 0xFF));
        buf.write((byte)(messageExpiryInterval & 0xFF));
        buf.write((byte)35);
        buf.write((byte)(topicAlias >> 8 & 0xFF));
        buf.write((byte)(topicAlias & 0xFF));
        buf.write((byte)8);
        int responseTopicLen = responseTopic.getBytes(StandardCharsets.UTF_8).length;
        buf.write((byte)(responseTopicLen >> 8 & 0xFF));
        buf.write((byte)(responseTopicLen & 0xFF));
        buf.write(responseTopic.getBytes(StandardCharsets.UTF_8));
        buf.write((byte)9);
        int correlationDataLen = correlationData.length;
        buf.write((byte)(correlationDataLen >> 8 & 0xFF));
        buf.write((byte)(correlationDataLen & 0xFF));
        buf.write(correlationData);
        buf.write((byte)38);
        int key1Len = key1.getBytes(StandardCharsets.UTF_8).length;
        buf.write((byte)(key1Len >> 8 & 0xFF));
        buf.write((byte)(key1Len & 0xFF));
        buf.write(key1.getBytes(StandardCharsets.UTF_8));
        int val1Len = val1.getBytes(StandardCharsets.UTF_8).length;
        buf.write((byte)(val1Len >> 8 & 0xFF));
        buf.write((byte)(val1Len & 0xFF));
        buf.write(val1.getBytes(StandardCharsets.UTF_8));
        buf.write((byte)11);
        writeMqttVarInt(buf, subscriptionIdentifier);
        buf.write((byte)3);
        int cotentTypeLen = contentType.getBytes(StandardCharsets.UTF_8).length;
        buf.write((byte)(cotentTypeLen >> 8 & 0xFF));
        buf.write((byte)(cotentTypeLen & 0xFF));
        buf.write(contentType.getBytes(StandardCharsets.UTF_8));

        buf.write(payload.getBytes(StandardCharsets.UTF_8));

        parseCtx = new ParseContext();
        parseCtx.setSession(new MqttNioSessionV5());
        parseCtx.setRpos(buf.rpos());
        parseCtx.setParseData(new byte[4]);
        parseCtx.setRpos(buf.rpos() + 1);
        parseCtx.setResult(new ParseResult<>());
        buf.wpos(buf.wpos());
        r = decoder.parse(buf, fixHeader  | (1 << 3) | (1 << 2), parseCtx);
        assertEquals(r.getMessage() instanceof Publish, true);
        publish = (Publish) r.getMessage();
        assertEquals(publish.getDup(), 1);
        assertEquals(publish.getQos(), QoSLevel.EXACTLY_ONCE);
        assertEquals(publish.getRetain(), 0);
        LinkedHashMap<PropertyType, PacketProperty> props = publish.getProperties();
        assertEquals(props.size(), 8);
        assertEquals(props.containsKey(PropertyType.PAYLOAD_FORMAT_INDICATOR), true);
        assertEquals((byte)props.get(PropertyType.PAYLOAD_FORMAT_INDICATOR).value(), (byte)payloadFormatIndicator);
        assertEquals(props.containsKey(PropertyType.MESSAGE_EXPIRY_INTERVAL), true);
        assertEquals(props.get(PropertyType.MESSAGE_EXPIRY_INTERVAL).value(), messageExpiryInterval);
        assertEquals(props.containsKey(PropertyType.TOPIC_ALIAS), true);
        assertEquals(props.get(PropertyType.TOPIC_ALIAS).value(), topicAlias);
        assertEquals(props.containsKey(PropertyType.RESPONSE_TOPIC), true);
        assertEquals(props.get(PropertyType.RESPONSE_TOPIC).value(), responseTopic);
        assertEquals(props.containsKey(PropertyType.CORRELATION_DATA), true);
        assertArrayEquals((byte[])(props.get(PropertyType.CORRELATION_DATA).value()), correlationData);
        assertEquals(props.containsKey(PropertyType.USER_PROPERTY), true);
        assertEquals(props.get(PropertyType.USER_PROPERTY) instanceof UserProperty, true);
        UserProperty up = (UserProperty)props.get(PropertyType.USER_PROPERTY);
        List<StringPair> pairs = up.value();
        assertEquals(pairs.size(), 1);
        assertEquals(pairs.get(0).getName(), key1);
        assertEquals(pairs.get(0).getValue(), val1);
        assertEquals(props.containsKey(PropertyType.SUBSCRIPTION_INDENTIFIER), true);
        assertEquals(props.get(PropertyType.SUBSCRIPTION_INDENTIFIER).value(), subscriptionIdentifier);
        assertEquals(props.containsKey(PropertyType.CONTENT_TYPE), true);
        assertEquals(props.get(PropertyType.CONTENT_TYPE).value(), contentType);
        assertArrayEquals(publish.getPayload(), payload.getBytes(StandardCharsets.UTF_8));

        assertEquals(buf.wpos(), buf.rpos());

    }
}
