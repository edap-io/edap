package io.edap.mqtt.test;

import io.edap.mqtt.MqttWriter;
import io.edap.mqtt.PacketProperty;
import io.edap.mqtt.PropertyType;
import io.edap.mqtt.encoder.V31Encoder;
import io.edap.mqtt.encoder.V5Encoder;
import io.edap.mqtt.packet.Disconnect;
import io.edap.mqtt.packet.PingReq;
import io.edap.mqtt.property.*;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Random;

import static io.edap.mqtt.ControlPacketType.DISCONNECT_VALUE;
import static io.edap.mqtt.test.MqttEncoderConnectTest.writeProperties;
import static io.edap.mqtt.test.MqttEncoderConnectTest.writeVarInt;
import static io.edap.mqtt.test.TestUtil.randomStr;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;

public class MqttEncoderDisconnectTest {

    @Test
    public void testEncodeDisconnectV3x() {
        V31Encoder v31Encoder = new V31Encoder();
        int fixedByteValue = DISCONNECT_VALUE << 4;
        Disconnect disconnect = new Disconnect(fixedByteValue);
        MqttWriter writer = new MqttWriter();
        v31Encoder.encode(writer, disconnect);
        byte[] data = new byte[writer.getLength()];
        System.arraycopy(writer.getData(), writer.getStart(), data, 0, data.length);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(fixedByteValue);
        out.write(0);
        byte[] expect = out.toByteArray();
        assertArrayEquals(expect, data);
    }

    @Test
    public void testEncodeDisconnectV5() throws IOException {
        Random random = new Random();
        V5Encoder v5Encoder = new V5Encoder();
        int fixedByteValue = DISCONNECT_VALUE << 4;
        int reasonCode = Byte.MAX_VALUE + random.nextInt(Byte.MAX_VALUE);
        int sessionExpiryInterval = Short.MAX_VALUE + random.nextInt(Short.MAX_VALUE);
        String reason = randomStr(5 + random.nextInt(20));
        String serverReference = randomStr(10 + random.nextInt(30));
        Disconnect disconnect = new Disconnect(fixedByteValue);
        disconnect.setReasonCode(reasonCode);
        LinkedHashMap<PropertyType, PacketProperty> props = new LinkedHashMap<>();
        props.put(PropertyType.SESSION_EXPIRY_INTERVAL, new SessionExpiryInterval(sessionExpiryInterval));
        props.put(PropertyType.REASON_STRING, new ReasonString(reason));
        UserProperty up = new UserProperty();
        String key1 = randomStr(5 + new Random().nextInt(10));
        String val1 = randomStr(10 + new Random().nextInt(20));
        StringPair pair1 = new StringPair(key1, val1);
        up.value(Arrays.asList(pair1));
        props.put(PropertyType.USER_PROPERTY, up);
        props.put(PropertyType.SERVER_REFERENCE, new ServerReference(serverReference));
        disconnect.setProperties(props);

        MqttWriter writer = new MqttWriter();
        v5Encoder.encode(writer, disconnect);
        byte[] data = new byte[writer.getLength()];
        System.arraycopy(writer.getData(), writer.getStart(), data, 0, data.length);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(reasonCode);
        ByteArrayOutputStream propOut = new ByteArrayOutputStream();
        writeProperties(propOut, props);
        byte[] propData = propOut.toByteArray();
        writeVarInt(out, propData.length);
        out.write(propData);
        byte[] disconnectData = out.toByteArray();
        out.reset();
        out.write(fixedByteValue);
        writeVarInt(out, disconnectData.length);
        out.write(disconnectData);
        byte[] expect = out.toByteArray();
        assertArrayEquals(expect, data);


        writer = new MqttWriter();
        writer.setStart(6);
        v5Encoder.encode(writer, disconnect);
        data = new byte[writer.getLength()];
        System.arraycopy(writer.getData(), writer.getStart(), data, 0, data.length);
        assertArrayEquals(expect, data);
    }
}
