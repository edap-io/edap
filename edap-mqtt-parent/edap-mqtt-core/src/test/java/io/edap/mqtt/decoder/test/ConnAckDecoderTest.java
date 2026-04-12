package io.edap.mqtt.decoder.test;

import io.edap.buffer.FastBuf;
import io.edap.mqtt.*;
import io.edap.mqtt.decoder.ConnAckDecoder;
import io.edap.mqtt.packet.Auth;
import io.edap.mqtt.packet.ConnAck;
import io.edap.mqtt.property.UserProperty;
import io.edap.mqtt.test.MqttNioSessionV311;
import io.edap.mqtt.test.MqttNioSessionV5;
import io.edap.nio.ParseResult;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Random;

import static io.edap.mqtt.test.TestUtil.randomStr;
import static io.edap.mqtt.test.TestUtil.writeMqttVarInt;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class ConnAckDecoderTest {

    @Test
    public void testParse() {
        ParseResult<ControlPacket> r;
        ConnAckDecoder decoder = new ConnAckDecoder();
        FastBuf buf = new FastBuf(8);
        int fixHeader = (ControlPacketType.CONNACK_VALUE << 4);

        int len = 127;
        buf.write((byte) fixHeader);
        writeMqttVarInt(buf, len);
        ParseContext parseCtx = new ParseContext();
        parseCtx.setSession(new MqttNioSessionV311());

        parseCtx.setRpos(buf.rpos() + 4);
        r = decoder.parse(buf, fixHeader, parseCtx);
        assertEquals(r.isFinished(), false);

        parseCtx.setRpos(buf.rpos() + 1);
        r = decoder.parse(buf, fixHeader, parseCtx);
        assertEquals(r.isFinished(), false);

        len = 128;
        buf.reset();
        buf.write((byte) fixHeader);
        writeMqttVarInt(buf, len);
        parseCtx.setRpos(buf.rpos() + 1);
        r = decoder.parse(buf, fixHeader, parseCtx);
        assertEquals(r.isFinished(), false);

        parseCtx.setRpos(buf.rpos() + 1);
        buf.wpos(buf.wpos() - 1);
        r = decoder.parse(buf, fixHeader, parseCtx);
        assertEquals(r.isFinished(), false);


        len = 16384;
        buf.reset();
        buf.write((byte) fixHeader);
        writeMqttVarInt(buf, len);
        parseCtx.setRpos(buf.rpos() + 1);
        r = decoder.parse(buf, fixHeader, parseCtx);
        assertEquals(r.isFinished(), false);

        parseCtx.setRpos(buf.rpos() + 1);
        buf.wpos(buf.wpos() - 1);
        r = decoder.parse(buf, fixHeader, parseCtx);
        assertEquals(r.isFinished(), false);


        len = 2097152;
        buf.reset();
        buf.write((byte) fixHeader);
        writeMqttVarInt(buf, len);
        parseCtx.setRpos(buf.rpos() + 1);
        r = decoder.parse(buf, fixHeader, parseCtx);
        assertEquals(r.isFinished(), false);

        parseCtx.setRpos(buf.rpos() + 1);
        buf.wpos(buf.wpos() - 1);
        r = decoder.parse(buf, fixHeader, parseCtx);
        assertEquals(r.isFinished(), false);


        len = 2;
        buf.reset();
        buf.write((byte) fixHeader);
        writeMqttVarInt(buf, len);
        buf.write((byte) 1);
        buf.write((byte) 0);
        parseCtx.setRpos(buf.rpos() + 1);
        r = decoder.parse(buf, fixHeader, parseCtx);
        assertEquals(r.isFinished(), true);
        assertEquals(r.getMessage() instanceof ConnAck, true);
        ConnAck connAck = (ConnAck) r.getMessage();
        assertEquals(connAck.getConnAckFlag(), 1);
        assertEquals(connAck.getConnAckCode(), 0);
        assertEquals(buf.rpos(), buf.wpos());

        buf.reset();
        buf.write((byte) fixHeader);
        writeMqttVarInt(buf, len);
        buf.write((byte) 0);
        buf.write((byte) 2);
        parseCtx.setRpos(buf.rpos() + 1);
        r = decoder.parse(buf, fixHeader, parseCtx);
        assertEquals(r.isFinished(), true);
        assertEquals(r.getMessage() instanceof ConnAck, true);
        connAck = (ConnAck) r.getMessage();
        assertEquals(connAck.getConnAckFlag(), 0);
        assertEquals(connAck.getConnAckCode(), 2);
        assertEquals(buf.rpos(), buf.wpos());

        len = 3;
        parseCtx.setSession(new MqttNioSessionV5());
        buf.reset();
        buf.write((byte) fixHeader);
        writeMqttVarInt(buf, len);
        buf.write((byte) 0);
        buf.write((byte) 2);
        buf.write((byte) 0);
        parseCtx.setRpos(buf.rpos() + 1);
        r = decoder.parse(buf, fixHeader, parseCtx);
        assertEquals(r.isFinished(), true);
        assertEquals(r.getMessage() instanceof ConnAck, true);
        connAck = (ConnAck) r.getMessage();
        assertEquals(connAck.getConnAckFlag(), 0);
        assertEquals(connAck.getConnAckCode(), 2);
        assertEquals(buf.rpos(), buf.wpos());

        Random random = new Random();
        int connFlag = random.nextInt(Byte.MAX_VALUE);
        int reasonCode = Byte.MAX_VALUE + random.nextInt(Byte.MAX_VALUE);
        int sessionExpiryInterval = Short.MAX_VALUE + random.nextInt(Short.MAX_VALUE);
        int receiveMaximum = Short.MAX_VALUE + random.nextInt(Short.MAX_VALUE);
        int maxQoS = random.nextInt(3);
        int retainAvail = random.nextInt(Byte.MAX_VALUE);
        int maxPacketSize = Short.MAX_VALUE + random.nextInt(Short.MAX_VALUE * 2);
        String assignedClientIdentifier = randomStr(30 + random.nextInt(20));
        int topicAliasMaximum = Short.MAX_VALUE + random.nextInt(Short.MAX_VALUE);
        String reasonStr = randomStr(20 + random.nextInt(20));
        String key1 = randomStr(5 + new Random().nextInt(10));
        String val1 = randomStr(10 + new Random().nextInt(20));
        int wildcardSubscriptionAvailable = 1 + random.nextInt(Byte.MAX_VALUE);
        int subscriptionIdentifiersAvailable = 5 + random.nextInt(Byte.MAX_VALUE);
        int sharedSubscriptionAvailable = 10 + random.nextInt(Byte.MAX_VALUE);
        int serverKeepAlive = Short.MAX_VALUE + random.nextInt(Short.MAX_VALUE);
        String responseInformation = randomStr(25 + random.nextInt(20));
        String serverReference = randomStr(15 + random.nextInt(20));
        String authMethod = randomStr(10 + random.nextInt(5));
        byte[] authData = randomStr(50 + random.nextInt(20)).getBytes(StandardCharsets.UTF_8);
        int connPropsLen = 5 + // sessionExpiryInterval
                3 + // receiveMaximum
                2 + // maxQoS
                2 + // retainAvail
                5 + // maxPacketSize
                3 + assignedClientIdentifier.getBytes(StandardCharsets.UTF_8).length + // assignedClientIdentifier
                3 + // topicAliasMaximum
                3 + reasonStr.getBytes(StandardCharsets.UTF_8).length +
                3 + key1.getBytes(StandardCharsets.UTF_8).length +
                2 + val1.getBytes(StandardCharsets.UTF_8).length + // userproperty
                2 + // wildcardSubscriptionAvailable
                2 + // subscriptionIdentifiersAvailable
                2 + // sharedSubscriptionAvailable
                3 + // serverKeepAlive
                3 + responseInformation.getBytes(StandardCharsets.UTF_8).length + // responseInformation
                3 + serverReference.getBytes(StandardCharsets.UTF_8).length + // serverReference
                3 + authMethod.getBytes(StandardCharsets.UTF_8).length + // authMethod
                3 + authData.length; // authData
        int varLen;
        if (connPropsLen > 2097151) {
            varLen = 4;
        } else if (connPropsLen > 16383) {
            varLen = 3;
        } else if (connPropsLen > 127) {
            varLen = 2;
        } else {
            varLen = 1;
        }
        len = 2 + varLen + connPropsLen;
        parseCtx.setSession(new MqttNioSessionV5());
        buf = new FastBuf(4096000);
        buf.reset();
        buf.write((byte) fixHeader);
        writeMqttVarInt(buf, len);
        buf.write((byte)connFlag);
        buf.write((byte) reasonCode);
        writeMqttVarInt(buf, connPropsLen);
        buf.write((byte)17);
        buf.write((byte)((sessionExpiryInterval >> 24) & 0xFF));
        buf.write((byte)((sessionExpiryInterval >> 16) & 0xFF));
        buf.write((byte)((sessionExpiryInterval >> 8) & 0xFF));
        buf.write((byte)(sessionExpiryInterval & 0xFF));
        buf.write((byte)33);
        buf.write((byte)((receiveMaximum >> 8) & 0xFF));
        buf.write((byte)(receiveMaximum & 0xFF));
        buf.write((byte)36);
        buf.write((byte)(maxQoS & 0xFF));
        buf.write((byte)37);
        buf.write((byte)(retainAvail & 0xFF));
        buf.write((byte)39);
        buf.write((byte)((maxPacketSize >> 24) & 0xFF));
        buf.write((byte)((maxPacketSize >> 16) & 0xFF));
        buf.write((byte)((maxPacketSize >> 8) & 0xFF));
        buf.write((byte)(maxPacketSize & 0xFF));
        buf.write((byte)18);
        int assignedClientIdentifierLen = assignedClientIdentifier.getBytes(StandardCharsets.UTF_8).length;
        buf.write((byte)((assignedClientIdentifierLen >> 8) & 0xFF));
        buf.write((byte)(assignedClientIdentifierLen & 0xFF));
        buf.write(assignedClientIdentifier.getBytes(StandardCharsets.UTF_8));
        buf.write((byte)34);
        buf.write((byte)((topicAliasMaximum >> 8) & 0xFF));
        buf.write((byte)(topicAliasMaximum & 0xFF));
        buf.write((byte)31);
        int reasonStrLen = reasonStr.getBytes(StandardCharsets.UTF_8).length;
        buf.write((byte)((reasonStrLen >> 8) & 0xFF));
        buf.write((byte)(reasonStrLen & 0xFF));
        buf.write(reasonStr.getBytes(StandardCharsets.UTF_8));
        buf.write((byte)38);
        int key1Len = key1.getBytes(StandardCharsets.UTF_8).length;
        buf.write((byte)((key1Len >> 8) & 0xFF));
        buf.write((byte)(key1Len & 0xFF));
        buf.write(key1.getBytes(StandardCharsets.UTF_8));
        int val1Len = val1.getBytes(StandardCharsets.UTF_8).length;
        buf.write((byte)((val1Len >> 8) & 0xFF));
        buf.write((byte)(val1Len & 0xFF));
        buf.write(val1.getBytes(StandardCharsets.UTF_8));
        buf.write((byte)40);
        buf.write((byte)wildcardSubscriptionAvailable);
        buf.write((byte)41);
        buf.write((byte)subscriptionIdentifiersAvailable);
        buf.write((byte)42);
        buf.write((byte)sharedSubscriptionAvailable);
        buf.write((byte)19);
        buf.write((byte)((serverKeepAlive >> 8) & 0xFF));
        buf.write((byte)(serverKeepAlive & 0xFF));
        buf.write((byte)26);
        int respInfoLen = responseInformation.getBytes(StandardCharsets.UTF_8).length;
        buf.write((byte)((respInfoLen >> 8) & 0xFF));
        buf.write((byte)(respInfoLen & 0xFF));
        buf.write(responseInformation.getBytes(StandardCharsets.UTF_8));
        buf.write((byte)28);
        int serverRefLen = serverReference.getBytes(StandardCharsets.UTF_8).length;
        buf.write((byte)((serverRefLen >> 8) & 0xFF));
        buf.write((byte)(serverRefLen & 0xFF));
        buf.write(serverReference.getBytes(StandardCharsets.UTF_8));
        buf.write((byte)21);
        int authMethodLen = authMethod.getBytes(StandardCharsets.UTF_8).length;
        buf.write((byte)((authMethodLen >> 8) & 0xFF));
        buf.write((byte)(authMethodLen & 0xFF));
        buf.write(authMethod.getBytes(StandardCharsets.UTF_8));
        buf.write((byte)22);
        int authDataLen = authData.length;
        buf.write((byte)((authDataLen >> 8) & 0xFF));
        buf.write((byte)(authDataLen & 0xFF));
        buf.write(authData);



        parseCtx.setRpos(buf.rpos() + 1);
        parseCtx.setParseData(new byte[4]);
        r = decoder.parse(buf, fixHeader, parseCtx);
        assertEquals(r.isFinished(), true);
        assertEquals(r.getMessage() instanceof ConnAck, true);
        connAck = (ConnAck) r.getMessage();
        assertEquals(connAck.getConnAckFlag(), connFlag);
        assertEquals(connAck.getConnAckCode(), reasonCode);
        LinkedHashMap<PropertyType, PacketProperty> props = connAck.getProperties();
        assertEquals(props.size(), 17);
        assertEquals(props.get(PropertyType.SESSION_EXPIRY_INTERVAL).value(), sessionExpiryInterval);
        assertEquals(props.get(PropertyType.RECEIVE_MAXINUM).value(), receiveMaximum);
        assertEquals(props.get(PropertyType.MAXIMUM_QOS).value(), (byte)maxQoS);
        assertEquals(props.get(PropertyType.RETAIN_AVAILABLE).value(), (byte)retainAvail);
        assertEquals(props.get(PropertyType.MAXIMUM_PACKET_SIZE).value(), maxPacketSize);
        assertEquals(props.get(PropertyType.ASSIGNED_CLIENT_IDENTIFIER).value(), assignedClientIdentifier);
        assertEquals(props.get(PropertyType.TOPIC_ALIAS_MAXIMUM).value(), topicAliasMaximum);
        assertEquals(props.get(PropertyType.REASON_STRING).value(), reasonStr);
        assertEquals(props.get(PropertyType.USER_PROPERTY) instanceof UserProperty, true);
        UserProperty up = (UserProperty)props.get(PropertyType.USER_PROPERTY);
        assertEquals(up.value().size(), 1);
        assertEquals(up.value().get(0).getName(), key1);
        assertEquals(up.value().get(0).getValue(), val1);
        assertEquals(props.get(PropertyType.REASON_STRING).value(), reasonStr);
        assertEquals(props.get(PropertyType.SUBSCRIPTION_INDENTIFIER_AVAILABLE).value(), (byte)subscriptionIdentifiersAvailable);
        assertEquals(props.get(PropertyType.SHARED_SUBSCRIPTION_AVAILABLE).value(), (byte)sharedSubscriptionAvailable);
        assertEquals(props.get(PropertyType.SERVER_KEEP_ALIVE).value(), serverKeepAlive);
        assertEquals(props.get(PropertyType.RESPONSE_INFORMATION).value(), responseInformation);
        assertEquals(props.get(PropertyType.SERVER_REFERENCE).value(), serverReference);
        assertEquals(props.get(PropertyType.AUTHENTICATION_METHOD).value(), authMethod);
        assertArrayEquals((byte[])props.get(PropertyType.AUTHENTICATION_DATA).value(), authData);

        assertEquals(buf.rpos(), buf.wpos());
    }
}
