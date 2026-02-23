package io.edap.mqtt.decoder.test;

import io.edap.buffer.FastBuf;
import io.edap.mqtt.ControlPacket;
import io.edap.mqtt.ControlPacketType;
import io.edap.mqtt.ParseContext;
import io.edap.mqtt.decoder.SubscribeDecoder;
import io.edap.mqtt.packet.Subscribe;
import io.edap.mqtt.packet.TopicFilter;
import io.edap.mqtt.test.MqttNioSessionV311;
import io.edap.mqtt.test.MqttNioSessionV5;
import io.edap.nio.ParseResult;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static io.edap.mqtt.test.TestUtil.randomStr;
import static io.edap.mqtt.test.TestUtil.writeMqttVarInt;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class SubscribeDecoderTest {

    @Test
    public void testParse() {
        SubscribeDecoder decoder = new SubscribeDecoder();
        ParseResult<ControlPacket> r;
        FastBuf buf = new FastBuf(4096);
        int fixHeader = (ControlPacketType.UNSUBSCRIBE_VALUE << 4);

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
        parseCtx = new ParseContext();
        parseCtx.setSession(new MqttNioSessionV311());
        parseCtx.setRpos(buf.rpos());
        parseCtx.setParseData(new byte[4]);
        parseCtx.setRpos(buf.rpos() + 1);
        parseCtx.setResult(new ParseResult<>());
        buf.wpos(buf.wpos() - 1);
        r = decoder.parse(buf, fixHeader, parseCtx);
        assertEquals(r.isFinished(), false);

        String str = randomStr(3);
        len = str.getBytes(StandardCharsets.UTF_8).length;
        buf.reset();
        buf.write((byte) fixHeader);
        writeMqttVarInt(buf, len);

        buf.write(str.getBytes(StandardCharsets.UTF_8));
        parseCtx = new ParseContext();
        parseCtx.setSession(new MqttNioSessionV311());
        parseCtx.setRpos(buf.rpos());
        parseCtx.setParseData(new byte[4]);
        parseCtx.setRpos(buf.rpos() + 1);
        parseCtx.setResult(new ParseResult<>());
        buf.wpos(buf.wpos() - 1);
        r = decoder.parse(buf, fixHeader, parseCtx);
        assertEquals(r.isFinished(), false);

        List<TopicFilter> filters = new ArrayList<>();
        int count = 1 + new Random().nextInt(10);
        int filterLen = 0;
        for (int i=0;i<count;i++) {
            TopicFilter tf = new TopicFilter();
            String topic = randomStr(5 + new Random().nextInt(60));
            int option = new Random().nextInt(3);
            tf.setTopicFilter(topic);
            tf.setSubscriptionOptions(option);
            filters.add(tf);
            filterLen += 2 + topic.getBytes(StandardCharsets.UTF_8).length + 1;
        }
        packetIdentifier = Short.MAX_VALUE  + new Random().nextInt(Short.MAX_VALUE);
        len = 2 + filterLen;
        buf.reset();
        buf.write((byte) fixHeader);
        writeMqttVarInt(buf, len);
        buf.write((byte)(packetIdentifier >> 8 & 0xFF));
        buf.write((byte)(packetIdentifier & 0xFF));
        for (int i=0;i<count;i++) {
            TopicFilter filter = filters.get(i);
            byte[] filterBs = filter.getTopicFilter().getBytes(StandardCharsets.UTF_8);
            int flen = filterBs.length;
            buf.write((byte)(flen >> 8 & 0xFF));
            buf.write((byte)(flen & 0xFF));
            buf.write(filterBs);
            buf.write((byte)(filter.getSubscriptionOptions() & 0xFF));
        }

        parseCtx = new ParseContext();
        parseCtx.setSession(new MqttNioSessionV311());
        parseCtx.setRpos(buf.rpos());
        parseCtx.setParseData(new byte[4]);
        parseCtx.setRpos(buf.rpos() + 1);
        parseCtx.setResult(new ParseResult<>());
        r = decoder.parse(buf, fixHeader, parseCtx);
        assertEquals(r.isFinished(), true);
        assertEquals(r.getMessage() instanceof Subscribe, true);
        Subscribe subscribe = (Subscribe) r.getMessage();
        assertEquals(subscribe.getPacketIdentifier(), packetIdentifier);
        assertEquals(subscribe.getTopicFilterList().size(), filters.size());
        count = filters.size();
        for (int i=0;i<count;i++) {
            assertEquals(subscribe.getTopicFilterList().get(i).getTopicFilter(), filters.get(i).getTopicFilter());
            assertEquals(subscribe.getTopicFilterList().get(i).getSubscriptionOptions(), filters.get(i).getSubscriptionOptions());
        }

        filters = new ArrayList<>();
        count = 1 + new Random().nextInt(10);
        filterLen = 0;
        for (int i=0;i<count;i++) {
            TopicFilter tf = new TopicFilter();
            String topic = randomStr(5 + new Random().nextInt(60));
            int option = new Random().nextInt(3);
            tf.setTopicFilter(topic);
            tf.setSubscriptionOptions(option);
            filters.add(tf);
            filterLen += 2 + topic.getBytes(StandardCharsets.UTF_8).length + 1;
        }
        packetIdentifier = Short.MAX_VALUE  + new Random().nextInt(Short.MAX_VALUE);
        String key1 = randomStr(5 + new Random().nextInt(10));
        String val1 = randomStr(10 + new Random().nextInt(20));
        int propLen = 0;
        int subscriptionIdentifier = 1 + new Random().nextInt(268435455);
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
        propLen += varLen;
        propLen += 3 + key1.getBytes(StandardCharsets.UTF_8).length +
                2 + val1.getBytes(StandardCharsets.UTF_8).length;

        if (propLen > 2097151) {
            varLen = 4;
        } else if (propLen > 16383) {
            varLen = 3;
        } else if (propLen > 127) {
            varLen = 2;
        } else {
            varLen = 1;
        }
        len = 2 + filterLen + propLen + varLen;
        buf.reset();
        buf.write((byte) fixHeader);
        writeMqttVarInt(buf, len);
        buf.write((byte)(packetIdentifier >> 8 & 0xFF));
        buf.write((byte)(packetIdentifier & 0xFF));
        writeMqttVarInt(buf, propLen);
        buf.write((byte)11);
        writeMqttVarInt(buf, subscriptionIdentifier);
        buf.write((byte)38);
        int key1Len = key1.getBytes(StandardCharsets.UTF_8).length;
        buf.write((byte)(key1Len >> 8 & 0xFF));
        buf.write((byte)(key1Len & 0xFF));
        buf.write(key1.getBytes(StandardCharsets.UTF_8));
        int val1Len = val1.getBytes(StandardCharsets.UTF_8).length;
        buf.write((byte)(val1Len >> 8 & 0xFF));
        buf.write((byte)(val1Len & 0xFF));
        buf.write(val1.getBytes(StandardCharsets.UTF_8));


        for (int i=0;i<count;i++) {
            TopicFilter filter = filters.get(i);
            byte[] filterBs = filter.getTopicFilter().getBytes(StandardCharsets.UTF_8);
            int flen = filterBs.length;
            buf.write((byte)(flen >> 8 & 0xFF));
            buf.write((byte)(flen & 0xFF));
            buf.write(filterBs);
            buf.write((byte)(filter.getSubscriptionOptions() & 0xFF));
        }

        parseCtx = new ParseContext();
        parseCtx.setSession(new MqttNioSessionV5());
        parseCtx.setRpos(buf.rpos());
        parseCtx.setParseData(new byte[4]);
        parseCtx.setRpos(buf.rpos() + 1);
        parseCtx.setResult(new ParseResult<>());
        r = decoder.parse(buf, fixHeader, parseCtx);
        assertEquals(r.isFinished(), true);
        assertEquals(r.getMessage() instanceof Subscribe, true);
        subscribe = (Subscribe) r.getMessage();
        assertEquals(subscribe.getPacketIdentifier(), packetIdentifier);
        assertEquals(subscribe.getTopicFilterList().size(), filters.size());
        count = filters.size();
        for (int i=0;i<count;i++) {
            assertEquals(subscribe.getTopicFilterList().get(i).getTopicFilter(), filters.get(i).getTopicFilter());
            assertEquals(subscribe.getTopicFilterList().get(i).getSubscriptionOptions(), filters.get(i).getSubscriptionOptions());
        }
        assertEquals(subscribe.getProperties().size(), 2);

        assertEquals(buf.wpos(), buf.rpos());
    }
}
