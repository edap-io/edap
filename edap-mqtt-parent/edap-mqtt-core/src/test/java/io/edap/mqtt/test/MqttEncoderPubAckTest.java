package io.edap.mqtt.test;

import io.edap.mqtt.MqttWriter;
import io.edap.mqtt.PacketProperty;
import io.edap.mqtt.PropertyType;
import io.edap.mqtt.QoSLevel;
import io.edap.mqtt.encoder.V31Encoder;
import io.edap.mqtt.encoder.V5Encoder;
import io.edap.mqtt.packet.PubAck;
import io.edap.mqtt.property.ReasonString;
import io.edap.mqtt.property.StringPair;
import io.edap.mqtt.property.UserProperty;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Random;

import static io.edap.mqtt.ControlPacketType.PUBACK_VALUE;
import static io.edap.mqtt.test.MqttEncoderConnectTest.writeProperties;
import static io.edap.mqtt.test.MqttEncoderConnectTest.writeVarInt;
import static io.edap.mqtt.test.TestUtil.randomStr;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;

public class MqttEncoderPubAckTest {

    @Test
    public void testEncodePubAckV3x() throws IOException {
        V31Encoder v31Encoder = new V31Encoder();
        Random random = new Random();
        int fixedByteValue = PUBACK_VALUE << 4;
        int dup = 1;
        QoSLevel qos = QoSLevel.MOST_ONCE;
        int retain = 1;
        int messageId = Short.MAX_VALUE + random.nextInt(Short.MAX_VALUE);
        fixedByteValue |= (dup & 0x1) << 3;
        fixedByteValue |= (qos.getValue() & 0x3) << 1;
        fixedByteValue |= retain & 0x1;
        PubAck pubAck = new PubAck(fixedByteValue);
        pubAck.setPacketIdentifier(messageId);

        MqttWriter writer = new MqttWriter();
        v31Encoder.encode(writer, pubAck);
        byte[] data = new byte[writer.getLength()];
        System.arraycopy(writer.getData(), writer.getStart(), data, 0, data.length);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(PUBACK_VALUE << 4 | (dup & 0x1) << 3 | (qos.getValue() & 0x3) << 1 | retain & 0x1);
        out.write(2);
        out.write(messageId >> 8);
        out.write(messageId);
        byte[] expect = out.toByteArray();
        assertArrayEquals(data, expect);

        dup = 0;
        qos = QoSLevel.LEAST_ONCE;
        retain = 0;
        pubAck.setDup(dup);
        pubAck.setQos(qos);
        pubAck.setRetain(retain);
        writer = new MqttWriter();
        v31Encoder.encode(writer, pubAck);
        data = new byte[writer.getLength()];
        System.arraycopy(writer.getData(), writer.getStart(), data, 0, data.length);
        out = new ByteArrayOutputStream();
        out.write(PUBACK_VALUE << 4 | (dup & 0x1) << 3 | (qos.getValue() & 0x3) << 1 | retain & 0x1);
        out.write(2);
        out.write(messageId >> 8);
        out.write(messageId);
        expect = out.toByteArray();
        assertArrayEquals(data, expect);

        writer = new MqttWriter();
        writer.setStart(13);
        v31Encoder.encode(writer, pubAck);
        data = new byte[writer.getLength()];
        System.arraycopy(writer.getData(), writer.getStart(), data, 0, data.length);
        assertArrayEquals(data, expect);
    }

    @Test
    public void testEncodePubAckV5() throws IOException {
        V5Encoder v5Encoder = new V5Encoder();
        Random random = new Random();
        int fixedByteValue = PUBACK_VALUE << 4;
        int dup = 1;
        QoSLevel qos = QoSLevel.MOST_ONCE;
        int retain = 1;
        int messageId = Short.MAX_VALUE + random.nextInt(Short.MAX_VALUE);
        int reasonCode = random.nextInt(Byte.MAX_VALUE * 2);
        String reason = randomStr(5 + random.nextInt(10));
        fixedByteValue |= (dup & 0x1) << 3;
        fixedByteValue |= (qos.getValue() & 0x3) << 1;
        fixedByteValue |= retain & 0x1;
        PubAck pubAck = new PubAck(fixedByteValue);
        pubAck.setPacketIdentifier(messageId);
        pubAck.setReasonCode(reasonCode);
        LinkedHashMap<PropertyType, PacketProperty> props = new LinkedHashMap<>();
        UserProperty up = new UserProperty();
        String key1 = randomStr(5 + new Random().nextInt(10));
        String val1 = randomStr(10 + new Random().nextInt(20));
        StringPair pair1 = new StringPair(key1, val1);
        up.value(Arrays.asList(pair1));
        props.put(PropertyType.REASON_STRING, new ReasonString(reason));
        props.put(PropertyType.USER_PROPERTY, up);
        pubAck.setReasonCode(reasonCode);
        pubAck.setProperties(props);

        MqttWriter writer = new MqttWriter();
        v5Encoder.encode(writer, pubAck);
        byte[] data = new byte[writer.getLength()];
        System.arraycopy(writer.getData(), writer.getStart(), data, 0, data.length);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(messageId >> 8);
        out.write(messageId);
        out.write(reasonCode & 0xFF);
        ByteArrayOutputStream propOut = new ByteArrayOutputStream();
        writeProperties(propOut, props);
        byte[] propData = propOut.toByteArray();
        writeVarInt(out, propData.length);
        out.write(propData);
        byte[] pubAckData = out.toByteArray();
        out.reset();
        out.write(PUBACK_VALUE << 4 | (dup & 0x1) << 3 | (qos.getValue() & 0x3) << 1 | retain & 0x1);
        writeVarInt(out, pubAckData.length);
        out.write(pubAckData);
        byte[] expect = out.toByteArray();
        assertArrayEquals(expect, data);

        dup = 0;
        qos = QoSLevel.LEAST_ONCE;
        retain = 0;
        pubAck.setDup(dup);
        pubAck.setQos(qos);
        pubAck.setRetain(retain);
        writer = new MqttWriter();
        v5Encoder.encode(writer, pubAck);
        data = new byte[writer.getLength()];
        System.arraycopy(writer.getData(), writer.getStart(), data, 0, data.length);

        out.reset();
        out.write(messageId >> 8);
        out.write(messageId);
        out.write(reasonCode & 0xFF);
        propOut = new ByteArrayOutputStream();
        writeProperties(propOut, props);
        propData = propOut.toByteArray();
        writeVarInt(out, propData.length);
        out.write(propData);
        pubAckData = out.toByteArray();
        out.reset();
        out.write(PUBACK_VALUE << 4 | (dup & 0x1) << 3 | (qos.getValue() & 0x3) << 1 | retain & 0x1);
        writeVarInt(out, pubAckData.length);
        out.write(pubAckData);
        expect = out.toByteArray();
        assertArrayEquals(expect, data);

        writer = new MqttWriter();
        writer.setStart(13);
        v5Encoder.encode(writer, pubAck);
        data = new byte[writer.getLength()];
        System.arraycopy(writer.getData(), writer.getStart(), data, 0, data.length);
        assertArrayEquals(data, expect);
    }
}
