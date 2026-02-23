package io.edap.mqtt.test;

import io.edap.mqtt.MqttWriter;
import io.edap.mqtt.PacketProperty;
import io.edap.mqtt.PropertyType;
import io.edap.mqtt.ProtocolLevel;
import io.edap.mqtt.encoder.V311Encoder;
import io.edap.mqtt.encoder.V31Encoder;
import io.edap.mqtt.encoder.V5Encoder;
import io.edap.mqtt.packet.Connect;
import io.edap.mqtt.property.*;
import io.edap.util.CollectionUtils;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;

import static io.edap.mqtt.test.TestUtil.randomStr;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class MqttEncoderConnectTest {

    @Test
    public void testEncodeConnectV31() throws IOException {
        Random random = new Random();
        V31Encoder v31Encoder = new V31Encoder();
        int fixedByteValue = 1 << 4;
        Connect connect = new Connect(fixedByteValue);
        int keepAlive = Short.MAX_VALUE + random.nextInt(Short.MAX_VALUE);
        String clientIdentifier = randomStr(3 + random.nextInt(40));
        String willTopic = randomStr(10 + random.nextInt(5));
        byte[] willPayload = randomStr(100 + random.nextInt(200)).getBytes(StandardCharsets.UTF_8);
        String username = randomStr(3 + random.nextInt(10));
        String password = randomStr(6 + random.nextInt(15));
        connect.setProtocolName("mqtt");
        connect.setProtocolLevel(ProtocolLevel.VERSION_3_1_1);
        connect.setUserNameFlag(1);
        connect.setPasswordFlag(1);
        connect.setWillFlag(1);
        connect.setWillQoS(1);
        connect.setWillRetain(1);
        connect.setCleanSessionFlag(1);
        connect.setKeepAlive(keepAlive);
        connect.setClientIdentifier(clientIdentifier);
        connect.setWillTopic(willTopic);
        connect.setWillPayload(willPayload);
        connect.setUserName(username);
        connect.setPassword(password);

        MqttWriter writer = new MqttWriter();
        v31Encoder.encode(writer, connect);
        byte[] data = new byte[writer.getLength()];
        System.arraycopy(writer.getData(), writer.getStart(), data, 0, data.length);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        //out.write(Integer.parseInt("10000", 2));
        out.write(0);
        out.write(6);
        out.write(new byte[]{'M', 'Q', 'I', 's', 'd', 'p'});
        out.write(3);
        out.write(Integer.parseInt("11101110", 2));
        out.write(keepAlive >> 8);
        out.write(keepAlive & 0xFF);
        byte[] clientIdentiferData =clientIdentifier.getBytes(StandardCharsets.UTF_8);
        int clientIdentiferDataLen = clientIdentiferData.length;
        out.write(clientIdentiferDataLen >> 8);
        out.write(clientIdentiferDataLen & 0xFF);
        out.write(clientIdentiferData);
        byte[] willTopicData = willTopic.getBytes(StandardCharsets.UTF_8);
        int willTopicDataLen = willTopicData.length;
        out.write(willTopicDataLen >> 8);
        out.write(willTopicDataLen & 0xFF);
        out.write(willTopicData);
        int willPayloadLen = willPayload.length;
        out.write(willPayloadLen >> 8);
        out.write(willPayloadLen & 0xFF);
        out.write(willPayload);
        byte[] usernameData = username.getBytes(StandardCharsets.UTF_8);
        int usernameDataLen = usernameData.length;
        out.write(usernameDataLen >> 8);
        out.write(usernameDataLen & 0xFF);
        out.write(usernameData);
        byte[] passwordData = password.getBytes(StandardCharsets.UTF_8);
        int passwordDataLen = passwordData.length;
        out.write(passwordDataLen >> 8);
        out.write(passwordDataLen & 0xFF);
        out.write(passwordData);
        byte[] connData = out.toByteArray();
        int dataLen = connData.length;
        int len = dataLen;
        int varLen;
        byte b1 = 0;
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
        expect[0] = (byte)Integer.parseInt("10000", 2);
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
        System.arraycopy(connData, 0, expect, varLen + 1, dataLen);
        assertArrayEquals(data, expect);


        connect.setUserNameFlag(0);
        connect.setPasswordFlag(0);
        connect.setWillFlag(0);
        connect.setWillQoS(0);
        connect.setWillRetain(0);
        connect.setCleanSessionFlag(0);
        out = new ByteArrayOutputStream();
        //out.write(Integer.parseInt("10000", 2));
        out.write(0);
        out.write(6);
        out.write(new byte[]{'M', 'Q', 'I', 's', 'd', 'p'});
        out.write(3);
        out.write(Integer.parseInt("00000000", 2));
        out.write(keepAlive >> 8);
        out.write(keepAlive & 0xFF);
        clientIdentiferData =clientIdentifier.getBytes(StandardCharsets.UTF_8);
        clientIdentiferDataLen = clientIdentiferData.length;
        out.write(clientIdentiferDataLen >> 8);
        out.write(clientIdentiferDataLen & 0xFF);
        out.write(clientIdentiferData);

        connData = out.toByteArray();
        dataLen = connData.length;
        len = dataLen;
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
        expect = new byte[1 + varLen + dataLen];
        expect[0] = (byte)Integer.parseInt("10000", 2);
        if (varLen == 1) {
            expect[1] = b1;
        } else if (varLen == 2) {
            expect[1] = b2;
            expect[2] = b1;
        } else if (varLen == 3) {
            expect[1] = b3;
            expect[2] = b2;
            expect[3] = b1;
        }  else if (varLen == 4) {
            expect[1] = b4;
            expect[2] = b3;
            expect[3] = b2;
            expect[4] = b1;
        }
        System.arraycopy(connData, 0, expect, varLen + 1, dataLen);
        writer = new MqttWriter();
        v31Encoder.encode(writer, connect);
        data = new byte[writer.getLength()];
        System.arraycopy(writer.getData(), writer.getStart(), data, 0, data.length);
        assertArrayEquals(data, expect);

        writer = new MqttWriter();
        writer.setStart(15);
        v31Encoder.encode(writer, connect);
        data = new byte[writer.getLength()];
        System.arraycopy(writer.getData(), writer.getStart(), data, 0, data.length);
        assertArrayEquals(data, expect);
    }

    @Test
    public void testEncodeConnectV311() throws IOException {
        Random random = new Random();
        V311Encoder v311Encoder = new V311Encoder();
        int fixedByteValue = 1 << 4;
        Connect connect = new Connect(fixedByteValue);
        int keepAlive = Short.MAX_VALUE + random.nextInt(Short.MAX_VALUE);
        String clientIdentifier = randomStr(3 + random.nextInt(40));
        String willTopic = randomStr(10 + random.nextInt(5));
        byte[] willPayload = randomStr(100 + random.nextInt(200)).getBytes(StandardCharsets.UTF_8);
        String username = randomStr(3 + random.nextInt(10));
        String password = randomStr(6 + random.nextInt(15));
        connect.setProtocolName("mqtt");
        connect.setProtocolLevel(ProtocolLevel.VERSION_3_1_1);
        connect.setUserNameFlag(1);
        connect.setPasswordFlag(1);
        connect.setWillFlag(1);
        connect.setWillQoS(1);
        connect.setWillRetain(1);
        connect.setCleanSessionFlag(1);
        connect.setKeepAlive(keepAlive);
        connect.setClientIdentifier(clientIdentifier);
        connect.setWillTopic(willTopic);
        connect.setWillPayload(willPayload);
        connect.setUserName(username);
        connect.setPassword(password);

        MqttWriter writer = new MqttWriter();
        v311Encoder.encode(writer, connect);
        byte[] data = new byte[writer.getLength()];
        System.arraycopy(writer.getData(), writer.getStart(), data, 0, data.length);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        //out.write(Integer.parseInt("10000", 2));
        out.write(0);
        out.write(4);
        out.write(new byte[]{'M', 'Q', 'T', 'T'});
        out.write(4);
        out.write(Integer.parseInt("11101110", 2));
        out.write(keepAlive >> 8);
        out.write(keepAlive & 0xFF);
        byte[] clientIdentiferData =clientIdentifier.getBytes(StandardCharsets.UTF_8);
        int clientIdentiferDataLen = clientIdentiferData.length;
        out.write(clientIdentiferDataLen >> 8);
        out.write(clientIdentiferDataLen & 0xFF);
        out.write(clientIdentiferData);
        byte[] willTopicData = willTopic.getBytes(StandardCharsets.UTF_8);
        int willTopicDataLen = willTopicData.length;
        out.write(willTopicDataLen >> 8);
        out.write(willTopicDataLen & 0xFF);
        out.write(willTopicData);
        int willPayloadLen = willPayload.length;
        out.write(willPayloadLen >> 8);
        out.write(willPayloadLen & 0xFF);
        out.write(willPayload);
        byte[] usernameData = username.getBytes(StandardCharsets.UTF_8);
        int usernameDataLen = usernameData.length;
        out.write(usernameDataLen >> 8);
        out.write(usernameDataLen & 0xFF);
        out.write(usernameData);
        byte[] passwordData = password.getBytes(StandardCharsets.UTF_8);
        int passwordDataLen = passwordData.length;
        out.write(passwordDataLen >> 8);
        out.write(passwordDataLen & 0xFF);
        out.write(passwordData);
        byte[] connData = out.toByteArray();
        int dataLen = connData.length;
        int len = dataLen;
        int varLen;
        byte b1 = 0;
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
        expect[0] = (byte)Integer.parseInt("10000", 2);
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
        System.arraycopy(connData, 0, expect, varLen + 1, dataLen);
        assertArrayEquals(data, expect);
    }

    @Test
    public void testEncodeConnectV5() throws IOException {
        Random random = new Random();
        V5Encoder v5Encoder = new V5Encoder();
        int fixedByteValue = 1 << 4;
        Connect connect = new Connect(fixedByteValue);
        int keepAlive = Short.MAX_VALUE + random.nextInt(Short.MAX_VALUE);
        String clientIdentifier = randomStr(3 + random.nextInt(40));
        String willTopic = randomStr(10 + random.nextInt(5));
        byte[] willPayload = randomStr(100 + random.nextInt(200)).getBytes(StandardCharsets.UTF_8);
        String username = randomStr(3 + random.nextInt(10));
        String password = randomStr(6 + random.nextInt(15));
        connect.setProtocolName("mqtt");
        connect.setProtocolLevel(ProtocolLevel.VERSION_5);
        connect.setUserNameFlag(1);
        connect.setPasswordFlag(1);
        connect.setWillFlag(1);
        connect.setWillQoS(1);
        connect.setWillRetain(1);
        connect.setCleanSessionFlag(1);
        connect.setKeepAlive(keepAlive);
        LinkedHashMap<PropertyType, PacketProperty> connProps = new LinkedHashMap<>();
        int sessionExpiryInterval = Short.MAX_VALUE + random.nextInt(Short.MAX_VALUE);
        int receiveMaximum = Short.MAX_VALUE + random.nextInt(Short.MAX_VALUE);
        int maximumPacketSize = Short.MAX_VALUE + random.nextInt(Short.MAX_VALUE);
        int topicAliasMaximum = Short.MAX_VALUE + random.nextInt(Short.MAX_VALUE);
        byte requestResponseInfo = (byte)(Byte.MAX_VALUE + random.nextInt(Byte.MAX_VALUE));
        byte requestProblemInfo = (byte)(Byte.MAX_VALUE + random.nextInt(Byte.MAX_VALUE));
        UserProperty up = new UserProperty();
        String key1 = randomStr(5 + new Random().nextInt(10));
        String val1 = randomStr(10 + new Random().nextInt(20));
        StringPair pair1 = new StringPair(key1, val1);
        up.value(Arrays.asList(pair1));
        String authMethod = randomStr(5 + random.nextInt(15));
        byte[] authData = randomStr(40 + random.nextInt(50)).getBytes(StandardCharsets.UTF_8);
        connProps.put(PropertyType.SESSION_EXPIRY_INTERVAL, new SessionExpiryInterval(sessionExpiryInterval));
        connProps.put(PropertyType.RECEIVE_MAXINUM, new ReceiveMaximum(receiveMaximum));
        connProps.put(PropertyType.MAXIMUM_PACKET_SIZE, new MaximumPacketSize(maximumPacketSize));
        connProps.put(PropertyType.TOPIC_ALIAS_MAXIMUM, new TopicAliasMaximum(topicAliasMaximum));
        connProps.put(PropertyType.REQUEST_RESPONSE_INFORMATION, new RequestResponseInformation(requestResponseInfo));
        connProps.put(PropertyType.REQUEST_PROBLEM_INFORMATION, new RequestProblemInformation(requestProblemInfo));
        connProps.put(PropertyType.USER_PROPERTY, up);
        connProps.put(PropertyType.AUTHENTICATION_METHOD, new AuthenticationMethod(authMethod));
        connProps.put(PropertyType.AUTHENTICATION_DATA, new AuthenticationData(authData));

        connect.setConnProperties(connProps);
        connect.setClientIdentifier(clientIdentifier);
        LinkedHashMap<PropertyType, PacketProperty> props = new LinkedHashMap<>();
        int willDelayInterval = Short.MAX_VALUE + random.nextInt(Short.MAX_VALUE);
        byte payloadFormatIndicator = (byte)(Byte.MAX_VALUE + random.nextInt(Byte.MAX_VALUE));
        int messageExpiryInterval = Short.MAX_VALUE + random.nextInt(Short.MAX_VALUE);
        String contentType = randomStr(10 + random.nextInt(10));
        String responseTopic = randomStr(15 + random.nextInt(5));
        byte[] correlationData = randomStr(50 + random.nextInt(20)).getBytes(StandardCharsets.UTF_8);
        UserProperty up2 = new UserProperty();
        String key2 = randomStr(5 + new Random().nextInt(10));
        String val2 = randomStr(10 + new Random().nextInt(20));
        StringPair pair2 = new StringPair(key2, val2);
        up2.value(Arrays.asList(pair2));
        props.put(PropertyType.WILL_DELAY_INTERVAL, new WillDelayInterval(willDelayInterval));
        props.put(PropertyType.PAYLOAD_FORMAT_INDICATOR, new PayloadFormatIndicator(payloadFormatIndicator));
        props.put(PropertyType.MESSAGE_EXPIRY_INTERVAL, new MessageExpiryInterval(messageExpiryInterval));
        props.put(PropertyType.CONTENT_TYPE, new ContentType(contentType));
        props.put(PropertyType.RESPONSE_TOPIC, new ResponseTopic(responseTopic));
        props.put(PropertyType.CORRELATION_DATA, new CorrelationData(correlationData));
        props.put(PropertyType.USER_PROPERTY, up2);
        connect.setProperties(props);

        connect.setWillTopic(willTopic);
        connect.setWillPayload(willPayload);
        connect.setUserName(username);
        connect.setPassword(password);


        MqttWriter writer = new MqttWriter();
        v5Encoder.encode(writer, connect);
        byte[] data = new byte[writer.getLength()];
        System.arraycopy(writer.getData(), writer.getStart(), data, 0, data.length);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        //out.write(Integer.parseInt("10000", 2));
        out.write(0);
        out.write(4);
        out.write(new byte[]{'M', 'Q', 'T', 'T'});
        out.write(5);
        out.write(Integer.parseInt("11101110", 2));
        out.write(keepAlive >> 8);
        out.write(keepAlive & 0xFF);

        ByteArrayOutputStream propOut = new ByteArrayOutputStream();
        writeProperties(propOut, connProps);
        byte[] connPropsData = propOut.toByteArray();
        writeVarInt(out, connPropsData.length);
        out.write(connPropsData);

        byte[] clientIdentiferData = clientIdentifier.getBytes(StandardCharsets.UTF_8);
        int clientIdentiferDataLen = clientIdentiferData.length;
        out.write(clientIdentiferDataLen >> 8);
        out.write(clientIdentiferDataLen & 0xFF);
        out.write(clientIdentiferData);

        propOut.reset();
        writeProperties(propOut, props);
        connPropsData = propOut.toByteArray();
        writeVarInt(out, connPropsData.length);
        out.write(connPropsData);

        byte[] willTopicData = willTopic.getBytes(StandardCharsets.UTF_8);
        int willTopicDataLen = willTopicData.length;
        out.write(willTopicDataLen >> 8);
        out.write(willTopicDataLen & 0xFF);
        out.write(willTopicData);
        int willPayloadLen = willPayload.length;
        out.write(willPayloadLen >> 8);
        out.write(willPayloadLen & 0xFF);
        out.write(willPayload);
        byte[] usernameData = username.getBytes(StandardCharsets.UTF_8);
        int usernameDataLen = usernameData.length;
        out.write(usernameDataLen >> 8);
        out.write(usernameDataLen & 0xFF);
        out.write(usernameData);
        byte[] passwordData = password.getBytes(StandardCharsets.UTF_8);
        int passwordDataLen = passwordData.length;
        out.write(passwordDataLen >> 8);
        out.write(passwordDataLen & 0xFF);
        out.write(passwordData);
        byte[] connData = out.toByteArray();
        int dataLen = connData.length;
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
        expect[0] = (byte)Integer.parseInt("10000", 2);
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
        System.arraycopy(connData, 0, expect, varLen + 1, dataLen);
        assertArrayEquals(data, expect);

        writer.reset();
        connect.setConnProperties(null);
        connect.setProperties(null);
        v5Encoder.encode(writer, connect);
        data = new byte[writer.getLength()];
        System.arraycopy(writer.getData(), writer.getStart(), data, 0, data.length);
        out = new ByteArrayOutputStream();
        //out.write(Integer.parseInt("10000", 2));
        out.write(0);
        out.write(4);
        out.write(new byte[]{'M', 'Q', 'T', 'T'});
        out.write(5);
        out.write(Integer.parseInt("11101110", 2));
        out.write(keepAlive >> 8);
        out.write(keepAlive & 0xFF);

        clientIdentiferData = clientIdentifier.getBytes(StandardCharsets.UTF_8);
        clientIdentiferDataLen = clientIdentiferData.length;
        out.write(clientIdentiferDataLen >> 8);
        out.write(clientIdentiferDataLen & 0xFF);
        out.write(clientIdentiferData);

        willTopicData = willTopic.getBytes(StandardCharsets.UTF_8);
        willTopicDataLen = willTopicData.length;
        out.write(willTopicDataLen >> 8);
        out.write(willTopicDataLen & 0xFF);
        out.write(willTopicData);
        willPayloadLen = willPayload.length;
        out.write(willPayloadLen >> 8);
        out.write(willPayloadLen & 0xFF);
        out.write(willPayload);
        usernameData = username.getBytes(StandardCharsets.UTF_8);
        usernameDataLen = usernameData.length;
        out.write(usernameDataLen >> 8);
        out.write(usernameDataLen & 0xFF);
        out.write(usernameData);
        passwordData = password.getBytes(StandardCharsets.UTF_8);
        passwordDataLen = passwordData.length;
        out.write(passwordDataLen >> 8);
        out.write(passwordDataLen & 0xFF);
        out.write(passwordData);
        connData = out.toByteArray();
        dataLen = connData.length;
        len = dataLen;
        b2 = 0;
        b3 = 0;
        b4 = 0;
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
        expect = new byte[1 + varLen + dataLen];
        expect[0] = (byte)Integer.parseInt("10000", 2);
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
        System.arraycopy(connData, 0, expect, varLen + 1, dataLen);
        assertArrayEquals(data, expect);
    }

    public static void writeProperties(OutputStream propOut, LinkedHashMap<PropertyType, PacketProperty> props)
            throws IOException {
        for (Map.Entry<PropertyType, PacketProperty> entry : props.entrySet()) {
            propOut.write(entry.getKey().getType());
            PacketProperty prop = entry.getValue();
            if (prop instanceof IntegerProperty) {
                int val = ((IntegerProperty)prop).value();
                propOut.write(val >> 24);
                propOut.write(val >> 16);
                propOut.write(val >> 8);
                propOut.write(val & 0xFF);
            } else if (prop instanceof ByteProperty) {
                propOut.write(((ByteProperty)prop).value());
            } else if (prop instanceof ByteArrayProperty) {
                byte[] bs = ((ByteArrayProperty)prop).value();
                int bsLen = bs.length;
                propOut.write(bsLen >> 8);
                propOut.write(bsLen);
                propOut.write(bs);
            } else if (prop instanceof TwoByteIntegerProperty) {
                int val = ((TwoByteIntegerProperty)prop).value();
                propOut.write(val >> 8);
                propOut.write(val);
            } else if (prop instanceof StringProperty) {
                String val = ((StringProperty)prop).value();
                byte[] sdata = val.getBytes(StandardCharsets.UTF_8);
                int valLen = sdata.length;
                propOut.write(valLen >> 8);
                propOut.write(valLen);
                propOut.write(sdata);
            } else if (prop instanceof SubscriptionIdentifier) {
                int val = ((SubscriptionIdentifier)prop).value();
                writeVarInt(propOut, val);
            } else if (prop instanceof UserProperty) {
                List<StringPair> pairs = ((UserProperty) prop).value();
                if (!CollectionUtils.isEmpty(pairs)) {
                    byte[] keyData, valData;
                    int keyLen, valLen;
                    boolean needWriteKey = false;
                    for (StringPair sp : pairs) {
                        if (needWriteKey) {
                            propOut.write(38);
                        } else {
                            needWriteKey = true;
                        }
                        keyData = sp.getName().getBytes(StandardCharsets.UTF_8);
                        valData = sp.getValue().getBytes(StandardCharsets.UTF_8);
                        keyLen = keyData.length;
                        valLen = valData.length;
                        propOut.write(keyLen >> 8);
                        propOut.write(keyLen);
                        propOut.write(keyData);
                        propOut.write(valLen >> 8);
                        propOut.write(valLen);
                        propOut.write(valData);
                    }
                }
            }
        }
    }

    public static void writeVarInt(OutputStream out, int val) throws IOException {
        byte b1 = 0;
        byte b2 = 0;
        byte b3 = 0;
        byte b4 = 0;
        if (val <= 127) {
            b1 = (byte)val;
            out.write(b1);
        } else if (val <= 16383) {
            b1 = (byte) ((val & 0x7F) | 0x80);
            val >>>= 7;
            b2 = (byte) val;
            out.write(b1);
            out.write(b2);
        } else if (val <= 2097151) {
            b1 = (byte) ((val & 0x7F) | 0x80);
            val >>>= 7;
            b2 = (byte) ((val & 0x7F) | 0x80);
            val >>>= 7;
            b3 = (byte) val;
            out.write(b1);
            out.write(b2);
            out.write(b3);
        } else {
            b1 = (byte) ((val & 0x7F) | 0x80);
            val >>>= 7;
            b2 = (byte) ((val & 0x7F) | 0x80);
            val >>>= 7;
            b3 = (byte) ((val & 0x7F) | 0x80);
            val >>>= 7;
            b4 = (byte) val;
            out.write(b1);
            out.write(b2);
            out.write(b3);
            out.write(b4);
        }
    }
}
