package io.edap.mqtt.test;

import io.edap.mqtt.MqttWriter;
import io.edap.mqtt.PacketProperty;
import io.edap.mqtt.PropertyType;
import io.edap.mqtt.encoder.V31Encoder;
import io.edap.mqtt.encoder.V5Encoder;
import io.edap.mqtt.packet.ConnAck;
import io.edap.mqtt.property.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

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

public class MqttEncoderConnAckTest {

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3, 4, 5})
    public void testEncodeConnAckV3x(int respCode) throws IOException {
        V31Encoder v31Encoder = new V31Encoder();
        int fixedByteValue = 2 << 4;
        ConnAck connAck = new ConnAck(fixedByteValue);
        connAck.setConnAckFlag(0);
        connAck.setConnAckCode(respCode);

        MqttWriter writer = new MqttWriter();
        v31Encoder.encode(writer, connAck);
        byte[] data = new byte[writer.getLength()];
        System.arraycopy(writer.getData(), writer.getStart(), data, 0, data.length);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(fixedByteValue);
        out.write(2);
        out.write(0);
        out.write(respCode);

        byte[] expect = out.toByteArray();
        assertArrayEquals(expect, data);
    }

    @ParameterizedTest
    @ValueSource(ints = {128, 129, 130, 131, 132, 133, 135, 135, 136, 137, 138,
            140, 144, 149, 151, 153, 154, 155, 156, 157, 159})
    public void testEncodeConnAckV5(int respCode) throws IOException {
        Random random = new Random();
        V5Encoder v31Encoder = new V5Encoder();
        int fixedByteValue = 2 << 4;
        ConnAck connAck = new ConnAck(fixedByteValue);
        connAck.setConnAckFlag(0);
        connAck.setConnAckCode(respCode);
        LinkedHashMap<PropertyType, PacketProperty> props = new LinkedHashMap<>();
        int sessionExpiryInterval = Short.MAX_VALUE + random.nextInt(Short.MAX_VALUE);
        int receiveMaximum = Short.MAX_VALUE + random.nextInt(Short.MAX_VALUE);
        byte maximumQoS = (byte)random.nextInt(3);
        byte retainAvailable = (byte)random.nextInt(Byte.MAX_VALUE);
        int maxPacketSize = Short.MAX_VALUE + random.nextInt(Short.MAX_VALUE);
        String assignedClientIdentifier = randomStr(5 + random.nextInt(20));
        int topicAliasMaximum = Short.MAX_VALUE + random.nextInt(Short.MAX_VALUE);
        String reason = randomStr(10 + random.nextInt(10));
        UserProperty up = new UserProperty();
        String key1 = randomStr(5 + new Random().nextInt(10));
        String val1 = randomStr(10 + new Random().nextInt(20));
        StringPair pair1 = new StringPair(key1, val1);
        up.value(Arrays.asList(pair1));
        byte wildcardSubAvailable = (byte)random.nextInt(Byte.MAX_VALUE);
        byte subIdentifierAvailable = (byte)random.nextInt(Byte.MAX_VALUE);
        byte shareSubAvailable = (byte)random.nextInt(Byte.MAX_VALUE);
        int serverKeepAlive = Short.MAX_VALUE + random.nextInt(Short.MAX_VALUE);
        String responseInfo = randomStr(10 + random.nextInt(10));
        String serverReference = randomStr(20 + random.nextInt(10));
        String authMethod = randomStr(5 + random.nextInt(10));
        byte[] authData = randomStr(50 + random.nextInt(20)).getBytes(StandardCharsets.UTF_8);
        props.put(PropertyType.SESSION_EXPIRY_INTERVAL, new SessionExpiryInterval(sessionExpiryInterval));
        props.put(PropertyType.RECEIVE_MAXINUM, new ReceiveMaximum(receiveMaximum));
        props.put(PropertyType.MAXIMUM_QOS, new MaximumQoS(maximumQoS));
        props.put(PropertyType.RETAIN_AVAILABLE, new RetainAvailable(retainAvailable));
        props.put(PropertyType.MAXIMUM_PACKET_SIZE, new MaximumPacketSize(maxPacketSize));
        props.put(PropertyType.ASSIGNED_CLIENT_IDENTIFIER, new AssignedClientIdentifier(assignedClientIdentifier));
        props.put(PropertyType.TOPIC_ALIAS_MAXIMUM, new TopicAliasMaximum(topicAliasMaximum));
        props.put(PropertyType.REASON_STRING, new ReasonString(reason));
        props.put(PropertyType.USER_PROPERTY, up);
        props.put(PropertyType.WILDCARD_SUBSCRIPTION_AVAILABLE, new WildcardSubscriptionAvailable(wildcardSubAvailable));
        props.put(PropertyType.SUBSCRIPTION_INDENTIFIER_AVAILABLE, new SubscriptionIdentifierAvailable(subIdentifierAvailable));
        props.put(PropertyType.SHARED_SUBSCRIPTION_AVAILABLE, new SharedSubscriptionAvailable(shareSubAvailable));
        props.put(PropertyType.SERVER_KEEP_ALIVE, new ServerKeepAlive(serverKeepAlive));
        props.put(PropertyType.RESPONSE_INFORMATION, new ResponseInformation(responseInfo));
        props.put(PropertyType.SERVER_REFERENCE, new ServerReference(serverReference));
        props.put(PropertyType.AUTHENTICATION_METHOD, new AuthenticationMethod(authMethod));
        props.put(PropertyType.AUTHENTICATION_DATA, new AuthenticationData(authData));
        connAck.setProperties(props);

        MqttWriter writer = new MqttWriter();
        v31Encoder.encode(writer, connAck);
        byte[] data = new byte[writer.getLength()];
        System.arraycopy(writer.getData(), writer.getStart(), data, 0, data.length);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0);
        out.write(respCode);
        ByteArrayOutputStream propOut = new ByteArrayOutputStream();
        writeProperties(propOut, props);
        byte[] connPropsData = propOut.toByteArray();
        writeVarInt(out, connPropsData.length);
        out.write(connPropsData);

        byte[] ackData = out.toByteArray();
        int dataLen = ackData.length;
        int len = dataLen;
        int varLen;
        byte b1;
        byte b2 = 0;
        byte b3 = 0;
        byte b4 = 0;
        if (len <= 127) {
            varLen = 1;
            b1 = (byte)len;
        } else if (len <= 16383) {
            varLen = 2;
            b1 = (byte) ((len & 0x7F) | 0x80);
            len >>>= 7;
            b2 = (byte) len;
        } else if (len <= 2097151) {
            varLen = 3;
            b1 = (byte) ((len & 0x7F) | 0x80);
            len >>>= 7;
            b2 = (byte) ((len & 0x7F) | 0x80);
            len >>>= 7;
            b3 = (byte) len;
        } else {
            varLen = 4;
            b1 = (byte) ((len & 0x7F) | 0x80);
            len >>>= 7;
            b2 = (byte) ((len & 0x7F) | 0x80);
            len >>>= 7;
            b3 = (byte) ((len & 0x7F) | 0x80);
            len >>>= 7;
            b4 = (byte) len;
        }
        byte[] expect = new byte[1 + varLen + dataLen];
        expect[0] = (byte)Integer.parseInt("100000", 2);
        if (varLen == 1) {
            expect[1] = b1;
        } else if (varLen == 2) {
            expect[1] = b1;
            expect[2] = b2;
        } else if (varLen == 3) {
            expect[1] = b1;
            expect[2] = b2;
            expect[3] = b3;
        }  else if (varLen == 4) {
            expect[1] = b1;
            expect[2] = b2;
            expect[3] = b3;
            expect[4] = b4;
        }
        System.arraycopy(ackData, 0, expect, varLen + 1, dataLen);
        assertArrayEquals(expect, data);


        writer = new MqttWriter();
        writer.setStart(17);
        v31Encoder.encode(writer, connAck);
        data = new byte[writer.getLength()];
        System.arraycopy(writer.getData(), writer.getStart(), data, 0, data.length);
        assertArrayEquals(expect, data);
    }
}
