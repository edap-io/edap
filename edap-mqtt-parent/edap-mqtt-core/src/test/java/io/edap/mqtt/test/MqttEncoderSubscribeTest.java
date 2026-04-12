package io.edap.mqtt.test;

import io.edap.mqtt.MqttWriter;
import io.edap.mqtt.PacketProperty;
import io.edap.mqtt.PropertyType;
import io.edap.mqtt.QoSLevel;
import io.edap.mqtt.encoder.V31Encoder;
import io.edap.mqtt.encoder.V5Encoder;
import io.edap.mqtt.packet.Subscribe;
import io.edap.mqtt.packet.TopicFilter;
import io.edap.mqtt.property.StringPair;
import io.edap.mqtt.property.SubscriptionIdentifier;
import io.edap.mqtt.property.UserProperty;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;

import static io.edap.mqtt.ControlPacketType.SUBSCRIBE_VALUE;
import static io.edap.mqtt.test.MqttEncoderConnectTest.writeProperties;
import static io.edap.mqtt.test.MqttEncoderConnectTest.writeVarInt;
import static io.edap.mqtt.test.TestUtil.randomStr;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;

public class MqttEncoderSubscribeTest {

    @Test
    public void testEncodeSubscribeV3x() throws IOException {
        V31Encoder v31Encoder = new V31Encoder();
        Random random = new Random();
        int fixedByteValue = SUBSCRIBE_VALUE << 4;
        int dup = 1;
        QoSLevel qos = QoSLevel.MOST_ONCE;
        int retain = 1;
        int messageId = Short.MAX_VALUE + random.nextInt(Short.MAX_VALUE);
        fixedByteValue |= (dup & 0x1) << 3;
        fixedByteValue |= (qos.getValue() & 0x3) << 1;
        fixedByteValue |= retain & 0x1;

        Subscribe subscribe = new Subscribe(fixedByteValue);
        subscribe.setPacketIdentifier(messageId);
        List<TopicFilter> topics = new ArrayList<>();
        String t1 = randomStr(5 + random.nextInt(5));
        String t2 = randomStr(10 + random.nextInt(5));
        int    option1 = 1;
        int    option2 = 2;
        TopicFilter topic1 = new TopicFilter();
        topic1.setTopicFilter(t1);
        topic1.setSubscriptionOptions(option1);
        TopicFilter topic2 = new TopicFilter();
        topic2.setTopicFilter(t2);
        topic2.setSubscriptionOptions(option2);
        topics.add(topic1);
        topics.add(topic2);
        subscribe.setTopicFilterList(topics);
        MqttWriter writer = new MqttWriter();
        v31Encoder.encode(writer, subscribe);
        byte[] data = new byte[writer.getLength()];
        System.arraycopy(writer.getData(), writer.getStart(), data, 0, data.length);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(messageId >> 8);
        out.write(messageId);
        for (TopicFilter t : topics) {
            byte[] tdata = t.getTopicFilter().getBytes(StandardCharsets.UTF_8);
            int tdataLen = tdata.length;
            out.write(tdataLen >> 8);
            out.write(tdataLen);
            out.write(tdata);
            out.write(t.getSubscriptionOptions());
        }
        byte[] subData = out.toByteArray();
        out.reset();
        out.write(fixedByteValue);
        writeVarInt(out, subData.length);
        out.write(subData);
        byte[] expect = out.toByteArray();
        assertArrayEquals(expect, data);


        writer = new MqttWriter();
        writer.setStart(8);
        v31Encoder.encode(writer, subscribe);
        data = new byte[writer.getLength()];
        System.arraycopy(writer.getData(), writer.getStart(), data, 0, data.length);
        assertArrayEquals(expect, data);
    }

    @Test
    public void testEncodeSubscribeV5() throws IOException {
        V5Encoder v5Encoder = new V5Encoder();
        Random random = new Random();
        int fixedByteValue = SUBSCRIBE_VALUE << 4;
        int dup = 1;
        QoSLevel qos = QoSLevel.MOST_ONCE;
        int retain = 1;
        int messageId = Short.MAX_VALUE + random.nextInt(Short.MAX_VALUE);
        fixedByteValue |= (dup & 0x1) << 3;
        fixedByteValue |= (qos.getValue() & 0x3) << 1;
        fixedByteValue |= retain & 0x1;

        Subscribe subscribe = new Subscribe(fixedByteValue);
        subscribe.setPacketIdentifier(messageId);
        List<TopicFilter> topics = new ArrayList<>();
        String t1 = randomStr(5 + random.nextInt(5));
        String t2 = randomStr(10 + random.nextInt(5));
        int    option1 = 1;
        int    option2 = 2;
        TopicFilter topic1 = new TopicFilter();
        topic1.setTopicFilter(t1);
        topic1.setSubscriptionOptions(option1);
        TopicFilter topic2 = new TopicFilter();
        topic2.setTopicFilter(t2);
        topic2.setSubscriptionOptions(option2);
        topics.add(topic1);
        topics.add(topic2);
        subscribe.setTopicFilterList(topics);
        int subscribeIdentifier = Short.MAX_VALUE + random.nextInt(268435455 - Short.MAX_VALUE);
        UserProperty up = new UserProperty();
        String key1 = randomStr(5 + new Random().nextInt(10));
        String val1 = randomStr(10 + new Random().nextInt(20));
        StringPair pair1 = new StringPair(key1, val1);
        up.value(Arrays.asList(pair1));
        LinkedHashMap<PropertyType, PacketProperty> props = new LinkedHashMap<>();
        props.put(PropertyType.SUBSCRIPTION_INDENTIFIER, new SubscriptionIdentifier(subscribeIdentifier));
        props.put(PropertyType.USER_PROPERTY, up);
        subscribe.setProperties(props);

        MqttWriter writer = new MqttWriter();
        v5Encoder.encode(writer, subscribe);
        byte[] data = new byte[writer.getLength()];
        System.arraycopy(writer.getData(), writer.getStart(), data, 0, data.length);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(messageId >> 8);
        out.write(messageId);
        ByteArrayOutputStream propOut = new ByteArrayOutputStream();
        writeProperties(propOut, props);
        byte[] propData = propOut.toByteArray();
        writeVarInt(out, propData.length);
        out.write(propData);
        for (TopicFilter t : topics) {
            byte[] tdata = t.getTopicFilter().getBytes(StandardCharsets.UTF_8);
            int tdataLen = tdata.length;
            out.write(tdataLen >> 8);
            out.write(tdataLen);
            out.write(tdata);
            out.write(t.getSubscriptionOptions());
        }
        byte[] subData = out.toByteArray();
        out.reset();
        out.write(fixedByteValue);
        writeVarInt(out, subData.length);
        out.write(subData);
        byte[] expect = out.toByteArray();
        assertArrayEquals(expect, data);


        writer = new MqttWriter();
        writer.setStart(8);
        v5Encoder.encode(writer, subscribe);
        data = new byte[writer.getLength()];
        System.arraycopy(writer.getData(), writer.getStart(), data, 0, data.length);
        assertArrayEquals(expect, data);
    }
}
