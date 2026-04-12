package io.edap.mqtt.test;

import io.edap.mqtt.MqttWriter;
import io.edap.mqtt.PacketProperty;
import io.edap.mqtt.PropertyType;
import io.edap.mqtt.encoder.V31Encoder;
import io.edap.mqtt.encoder.V5Encoder;
import io.edap.mqtt.packet.Auth;
import io.edap.mqtt.packet.Disconnect;
import io.edap.mqtt.property.*;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Random;

import static io.edap.mqtt.ControlPacketType.AUTH_VALUE;
import static io.edap.mqtt.test.MqttEncoderConnectTest.writeProperties;
import static io.edap.mqtt.test.MqttEncoderConnectTest.writeVarInt;
import static io.edap.mqtt.test.TestUtil.randomStr;
import static org.junit.jupiter.api.Assertions.*;

public class MqttEncoderAuthTest {

    @Test
    public void testEncodeAuthV3x() {
        V31Encoder v31Encoder = new V31Encoder();
        int fixedByteValue = AUTH_VALUE << 4;
        Auth auth= new Auth(fixedByteValue);
        MqttWriter writer = new MqttWriter();

        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> {
                    v31Encoder.encode(writer, auth);
                });
        assertTrue(thrown.getMessage().contains("Mqtt protocol level no supported!"));
    }

    @Test
    public void testEncodeAuthV5() throws IOException {
        Random random = new Random();
        V5Encoder v5Encoder = new V5Encoder();
        int fixedByteValue = AUTH_VALUE << 4;
        int reasonCode = random.nextInt(Byte.MAX_VALUE);
        String authMethod = randomStr(5 + random.nextInt(5));
        byte[] authData = randomStr(10 + random.nextInt(20)).getBytes(StandardCharsets.UTF_8);
        String reason = randomStr(30 + random.nextInt(10));
        Auth auth = new Auth(fixedByteValue);
        auth.setReasonCode(reasonCode);
        LinkedHashMap<PropertyType, PacketProperty> props = new LinkedHashMap<>();
        props.put(PropertyType.AUTHENTICATION_METHOD, new AuthenticationMethod(authMethod));
        props.put(PropertyType.AUTHENTICATION_DATA, new AuthenticationData(authData));
        props.put(PropertyType.REASON_STRING, new ReasonString(reason));
        UserProperty up = new UserProperty();
        String key1 = randomStr(5 + new Random().nextInt(10));
        String val1 = randomStr(10 + new Random().nextInt(20));
        StringPair pair1 = new StringPair(key1, val1);
        up.value(Arrays.asList(pair1));
        props.put(PropertyType.USER_PROPERTY, up);
        auth.setProperties(props);

        MqttWriter writer = new MqttWriter();
        v5Encoder.encode(writer, auth);
        byte[] data = new byte[writer.getLength()];
        System.arraycopy(writer.getData(), writer.getStart(), data, 0, data.length);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(reasonCode);
        ByteArrayOutputStream propOut = new ByteArrayOutputStream();
        writeProperties(propOut, props);
        byte[] propData = propOut.toByteArray();
        writeVarInt(out, propData.length);
        out.write(propData);
        byte[] authBs = out.toByteArray();
        out.reset();
        out.write(fixedByteValue);
        writeVarInt(out, authBs.length);
        out.write(authBs);
        byte[] expect = out.toByteArray();
        assertArrayEquals(expect, data);

        writer = new MqttWriter();
        writer.setStart(17);
        v5Encoder.encode(writer, auth);
        data = new byte[writer.getLength()];
        System.arraycopy(writer.getData(), writer.getStart(), data, 0, data.length);
    }
}
