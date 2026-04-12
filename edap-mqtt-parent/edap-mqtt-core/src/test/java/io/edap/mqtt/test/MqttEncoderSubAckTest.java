package io.edap.mqtt.test;

import io.edap.mqtt.MqttWriter;
import io.edap.mqtt.PacketProperty;
import io.edap.mqtt.PropertyType;
import io.edap.mqtt.QoSLevel;
import io.edap.mqtt.encoder.V31Encoder;
import io.edap.mqtt.encoder.V5Encoder;
import io.edap.mqtt.packet.SubAck;
import io.edap.mqtt.property.ReasonString;
import io.edap.mqtt.property.StringPair;
import io.edap.mqtt.property.UserProperty;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.*;

import static io.edap.mqtt.ControlPacketType.SUBACK_VALUE;
import static io.edap.mqtt.test.MqttEncoderConnectTest.writeProperties;
import static io.edap.mqtt.test.MqttEncoderConnectTest.writeVarInt;
import static io.edap.mqtt.test.TestUtil.randomStr;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;

public class MqttEncoderSubAckTest {

    @Test
    public void testEncodeSubAckV3x() throws IOException {
        V31Encoder v31Encoder = new V31Encoder();
        Random random = new Random();
        int fixedByteValue = SUBACK_VALUE << 4;
        int dup = 1;
        QoSLevel qos = QoSLevel.MOST_ONCE;
        int retain = 1;
        int messageId = Short.MAX_VALUE + random.nextInt(Short.MAX_VALUE);
        List<Integer> codes = new ArrayList<>();
        int count = random.nextInt(2 + random.nextInt(3));
        for (int i=0;i<count;i++) {
            codes.add(random.nextInt(Byte.MAX_VALUE));
        }
        fixedByteValue |= (dup & 0x1) << 3;
        fixedByteValue |= (qos.getValue() & 0x3) << 1;
        fixedByteValue |= retain & 0x1;

        SubAck subAck = new SubAck(fixedByteValue);
        subAck.setPacketIdentifier(messageId);
        subAck.setRespCodes(codes);

        MqttWriter writer = new MqttWriter();
        v31Encoder.encode(writer, subAck);
        byte[] data = new byte[writer.getLength()];
        System.arraycopy(writer.getData(), writer.getStart(), data, 0, data.length);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(messageId >> 8);
        out.write(messageId);
        for (int i=0;i<count;i++) {
            out.write(codes.get(i));
        }
        byte[] pubAckData = out.toByteArray();
        out.reset();
        out.write(fixedByteValue);
        writeVarInt(out, pubAckData.length);
        out.write(pubAckData);
        byte[] expect = out.toByteArray();
        assertArrayEquals(expect, data);

        writer = new MqttWriter();
        writer.setStart(9);
        v31Encoder.encode(writer, subAck);
        data = new byte[writer.getLength()];
        System.arraycopy(writer.getData(), writer.getStart(), data, 0, data.length);
    }

    @Test
    public void testEncodeSubAckV5() throws IOException {
        V5Encoder v5Encoder = new V5Encoder();
        Random random = new Random();
        int fixedByteValue = SUBACK_VALUE << 4;
        int dup = 1;
        QoSLevel qos = QoSLevel.MOST_ONCE;
        int retain = 1;
        int messageId = Short.MAX_VALUE + random.nextInt(Short.MAX_VALUE);
        List<Integer> codes = new ArrayList<>();
        int count = random.nextInt(2 + random.nextInt(3));
        for (int i=0;i<count;i++) {
            codes.add(random.nextInt(Byte.MAX_VALUE));
        }
        fixedByteValue |= (dup & 0x1) << 3;
        fixedByteValue |= (qos.getValue() & 0x3) << 1;
        fixedByteValue |= retain & 0x1;

        SubAck subAck = new SubAck(fixedByteValue);
        subAck.setPacketIdentifier(messageId);
        subAck.setRespCodes(codes);
        String reason = randomStr(5 + random.nextInt(20));
        LinkedHashMap<PropertyType, PacketProperty> props = new LinkedHashMap<>();
        props.put(PropertyType.REASON_STRING, new ReasonString(reason));
        UserProperty up = new UserProperty();
        String key1 = randomStr(5 + new Random().nextInt(10));
        String val1 = randomStr(10 + new Random().nextInt(20));
        StringPair pair1 = new StringPair(key1, val1);
        up.value(Arrays.asList(pair1));
        props.put(PropertyType.USER_PROPERTY, up);
        subAck.setProperties(props);

        MqttWriter writer = new MqttWriter();
        v5Encoder.encode(writer, subAck);
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
        for (int i=0;i<count;i++) {
            out.write(codes.get(i));
        }
        byte[] pubAckData = out.toByteArray();
        out.reset();
        out.write(fixedByteValue);
        writeVarInt(out, pubAckData.length);
        out.write(pubAckData);
        byte[] expect = out.toByteArray();
        assertArrayEquals(expect, data);

        writer = new MqttWriter();
        writer.setStart(9);
        v5Encoder.encode(writer, subAck);
        data = new byte[writer.getLength()];
        System.arraycopy(writer.getData(), writer.getStart(), data, 0, data.length);
    }
}
