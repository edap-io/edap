package io.edap.mqtt.test;

import io.edap.mqtt.MqttWriter;
import io.edap.mqtt.PacketProperty;
import io.edap.mqtt.PropertyType;
import io.edap.mqtt.QoSLevel;
import io.edap.mqtt.encoder.V31Encoder;
import io.edap.mqtt.encoder.V5Encoder;
import io.edap.mqtt.packet.Publish;
import io.edap.mqtt.property.*;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Random;

import static io.edap.mqtt.test.MqttEncoderConnectTest.writeProperties;
import static io.edap.mqtt.test.MqttEncoderConnectTest.writeVarInt;
import static io.edap.mqtt.test.TestUtil.randomStr;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;

public class MqttEncoderPublishTest {

    @Test
    public void testEncodePublishV3x() throws IOException {
        V31Encoder v31Encoder = new V31Encoder();
        Random random = new Random();
        int fixedByteValue = 3 << 4;
        String topic = randomStr(5 + random.nextInt(10));
        byte[] payload = randomStr(40 + new Random().nextInt(20)).getBytes(StandardCharsets.UTF_8);
        int packetIdentifier = Short.MAX_VALUE + random.nextInt(Short.MAX_VALUE);
        int dup = 1;
        QoSLevel qos = QoSLevel.LEAST_ONCE;
        int retain = 1;
        Publish publish = new Publish(fixedByteValue);
        publish.setDup(dup);
        publish.setQos(qos);
        publish.setRetain(retain);
        publish.setTopic(topic);
        publish.setPayload(payload);
        publish.setPacketIdentifier(packetIdentifier);

        MqttWriter writer = new MqttWriter();
        v31Encoder.encode(writer, publish);
        byte[] data = new byte[writer.getLength()];
        System.arraycopy(writer.getData(), writer.getStart(), data, 0, data.length);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] topicData = topic.getBytes(StandardCharsets.UTF_8);
        int topicLen = topicData.length;
        out.write(topicLen >> 8);
        out.write(topicLen & 0xFF);
        out.write(topicData);
        out.write(packetIdentifier >> 8);
        out.write(packetIdentifier & 0xFF);
        int payloadLen = payload.length;
        out.write(payloadLen >> 8);
        out.write(payloadLen & 0xFF);
        out.write(payload);

        byte[] pubData = out.toByteArray();
        out.reset();
        out.write((byte)(fixedByteValue | (publish.getDup() << 3) | (publish.getQos().getValue() << 1) | publish.getRetain()));
        writeVarInt(out, pubData.length);
        out.write(pubData);
        assertArrayEquals(data, out.toByteArray());


        dup = 0;
        qos = QoSLevel.EXACTLY_ONCE;
        retain = 0;
        publish.setDup(dup);
        publish.setQos(qos);
        publish.setRetain(retain);
        writer = new MqttWriter();
        v31Encoder.encode(writer, publish);
        data = new byte[writer.getLength()];
        System.arraycopy(writer.getData(), writer.getStart(), data, 0, data.length);

        out = new ByteArrayOutputStream();
        topicData = topic.getBytes(StandardCharsets.UTF_8);
        topicLen = topicData.length;
        out.write(topicLen >> 8);
        out.write(topicLen & 0xFF);
        out.write(topicData);
        out.write(packetIdentifier >> 8);
        out.write(packetIdentifier & 0xFF);
        payloadLen = payload.length;
        out.write(payloadLen >> 8);
        out.write(payloadLen & 0xFF);
        out.write(payload);

        pubData = out.toByteArray();
        out.reset();
        out.write((byte)(fixedByteValue | (publish.getDup() << 3) | (publish.getQos().getValue() << 1) | publish.getRetain()));
        writeVarInt(out, pubData.length);
        out.write(pubData);
        assertArrayEquals(data, out.toByteArray());

        dup = 1;
        qos = QoSLevel.MOST_ONCE;
        retain = 1;
        publish.setDup(dup);
        publish.setQos(qos);
        publish.setRetain(retain);
        writer = new MqttWriter();
        v31Encoder.encode(writer, publish);
        data = new byte[writer.getLength()];
        System.arraycopy(writer.getData(), writer.getStart(), data, 0, data.length);

        out = new ByteArrayOutputStream();
        topicData = topic.getBytes(StandardCharsets.UTF_8);
        topicLen = topicData.length;
        out.write(topicLen >> 8);
        out.write(topicLen & 0xFF);
        out.write(topicData);
        out.write(packetIdentifier >> 8);
        out.write(packetIdentifier & 0xFF);
        payloadLen = payload.length;
        out.write(payloadLen >> 8);
        out.write(payloadLen & 0xFF);
        out.write(payload);

        pubData = out.toByteArray();
        out.reset();
        out.write((byte)(fixedByteValue | (publish.getDup() << 3) | (publish.getQos().getValue() << 1) | publish.getRetain()));
        writeVarInt(out, pubData.length);
        out.write(pubData);
        assertArrayEquals(data, out.toByteArray());

        writer = new MqttWriter();
        writer.setStart(13);
        v31Encoder.encode(writer, publish);
        data = new byte[writer.getLength()];
        System.arraycopy(writer.getData(), writer.getStart(), data, 0, data.length);
        assertArrayEquals(data, out.toByteArray());
    }

    @Test
    public void testEncodePublishV5() throws IOException {
        ByteArrayOutputStream propOut = new ByteArrayOutputStream();
        V5Encoder v5Encoder = new V5Encoder();
        Random random = new Random();
        int fixedByteValue = 3 << 4;
        String topic = randomStr(5 + random.nextInt(10));
        byte[] payload = randomStr(40 + new Random().nextInt(20)).getBytes(StandardCharsets.UTF_8);
        int packetIdentifier = Short.MAX_VALUE + random.nextInt(Short.MAX_VALUE);
        int dup = 1;
        QoSLevel qos = QoSLevel.LEAST_ONCE;
        int retain = 1;
        Publish publish = new Publish(fixedByteValue);
        publish.setDup(dup);
        publish.setQos(qos);
        publish.setRetain(retain);
        publish.setTopic(topic);
        publish.setPayload(payload);
        publish.setPacketIdentifier(packetIdentifier);
        byte payloadFormatIndicator = (byte)random.nextInt(Byte.MAX_VALUE);
        int messageExpiryInterval = Short.MAX_VALUE + random.nextInt(Short.MAX_VALUE);
        int topicAlias = Short.MAX_VALUE + random.nextInt(Short.MAX_VALUE);
        String responseTopic = randomStr(10 + random.nextInt(10));
        byte[] correlationData = randomStr(50 + random.nextInt(40)).getBytes(StandardCharsets.UTF_8);
        UserProperty up = new UserProperty();
        String key1 = randomStr(5 + new Random().nextInt(10));
        String val1 = randomStr(10 + new Random().nextInt(20));
        StringPair pair1 = new StringPair(key1, val1);
        up.value(Arrays.asList(pair1));
        LinkedHashMap<PropertyType, PacketProperty> props = new LinkedHashMap<>();
        int subscriptionIdentifier = Short.MAX_VALUE + random.nextInt(Short.MAX_VALUE);
        String contentType = randomStr(5 + random.nextInt(10));
        props.put(PropertyType.PAYLOAD_FORMAT_INDICATOR, new PayloadFormatIndicator(payloadFormatIndicator));
        props.put(PropertyType.MESSAGE_EXPIRY_INTERVAL, new MessageExpiryInterval(messageExpiryInterval));
        props.put(PropertyType.TOPIC_ALIAS, new TopicAlias(topicAlias));
        props.put(PropertyType.RESPONSE_TOPIC, new ResponseTopic(responseTopic));
        props.put(PropertyType.CORRELATION_DATA, new CorrelationData(correlationData));
        props.put(PropertyType.USER_PROPERTY, up);
        props.put(PropertyType.SUBSCRIPTION_INDENTIFIER, new SubscriptionIdentifier(subscriptionIdentifier));
        props.put(PropertyType.CONTENT_TYPE, new ContentType(contentType));
        publish.setProperties(props);

        MqttWriter writer = new MqttWriter();
        v5Encoder.encode(writer, publish);
        byte[] data = new byte[writer.getLength()];
        System.arraycopy(writer.getData(), writer.getStart(), data, 0, data.length);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] topicData = topic.getBytes(StandardCharsets.UTF_8);
        int topicLen = topicData.length;
        out.write(topicLen >> 8);
        out.write(topicLen & 0xFF);
        out.write(topicData);
        out.write(packetIdentifier >> 8);
        out.write(packetIdentifier & 0xFF);
        propOut.reset();
        writeProperties(propOut, props);
        byte[] propsData = propOut.toByteArray();
        writeVarInt(out, propsData.length);
        out.write(propsData);
        int payloadLen = payload.length;
        out.write(payloadLen >> 8);
        out.write(payloadLen & 0xFF);
        out.write(payload);

        byte[] pubData = out.toByteArray();
        out.reset();
        out.write((byte)(fixedByteValue | (publish.getDup() << 3) | (publish.getQos().getValue() << 1) | publish.getRetain()));
        writeVarInt(out, pubData.length);
        out.write(pubData);
        byte[] expect = out.toByteArray();
        assertArrayEquals(data, expect);


        dup = 0;
        qos = QoSLevel.EXACTLY_ONCE;
        retain = 0;
        publish.setDup(dup);
        publish.setQos(qos);
        publish.setRetain(retain);
        writer = new MqttWriter();
        v5Encoder.encode(writer, publish);
        data = new byte[writer.getLength()];
        System.arraycopy(writer.getData(), writer.getStart(), data, 0, data.length);

        out = new ByteArrayOutputStream();
        topicData = topic.getBytes(StandardCharsets.UTF_8);
        topicLen = topicData.length;
        out.write(topicLen >> 8);
        out.write(topicLen & 0xFF);
        out.write(topicData);
        out.write(packetIdentifier >> 8);
        out.write(packetIdentifier & 0xFF);
        propOut.reset();
        writeProperties(propOut, props);
        propsData = propOut.toByteArray();
        writeVarInt(out, propsData.length);
        out.write(propsData);
        payloadLen = payload.length;
        out.write(payloadLen >> 8);
        out.write(payloadLen & 0xFF);
        out.write(payload);

        pubData = out.toByteArray();
        out.reset();
        out.write((byte)(fixedByteValue | (publish.getDup() << 3) | (publish.getQos().getValue() << 1) | publish.getRetain()));
        writeVarInt(out, pubData.length);
        out.write(pubData);
        assertArrayEquals(data, out.toByteArray());

        dup = 1;
        qos = QoSLevel.MOST_ONCE;
        retain = 1;
        publish.setDup(dup);
        publish.setQos(qos);
        publish.setRetain(retain);
        writer = new MqttWriter();
        v5Encoder.encode(writer, publish);
        data = new byte[writer.getLength()];
        System.arraycopy(writer.getData(), writer.getStart(), data, 0, data.length);

        out = new ByteArrayOutputStream();
        topicData = topic.getBytes(StandardCharsets.UTF_8);
        topicLen = topicData.length;
        out.write(topicLen >> 8);
        out.write(topicLen & 0xFF);
        out.write(topicData);
        out.write(packetIdentifier >> 8);
        out.write(packetIdentifier & 0xFF);

        propOut.reset();
        writeProperties(propOut, props);
        propsData = propOut.toByteArray();
        writeVarInt(out, propsData.length);
        out.write(propsData);
        payloadLen = payload.length;
        out.write(payloadLen >> 8);
        out.write(payloadLen & 0xFF);
        out.write(payload);

        pubData = out.toByteArray();
        out.reset();
        out.write((byte)(fixedByteValue | (publish.getDup() << 3) | (publish.getQos().getValue() << 1) | publish.getRetain()));
        writeVarInt(out, pubData.length);
        out.write(pubData);
        assertArrayEquals(data, out.toByteArray());

        writer = new MqttWriter();
        writer.setStart(13);
        v5Encoder.encode(writer, publish);
        data = new byte[writer.getLength()];
        System.arraycopy(writer.getData(), writer.getStart(), data, 0, data.length);
        assertArrayEquals(data, out.toByteArray());
    }
}
