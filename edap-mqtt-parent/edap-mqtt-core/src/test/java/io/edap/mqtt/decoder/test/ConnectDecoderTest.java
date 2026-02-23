package io.edap.mqtt.decoder.test;

import io.edap.buffer.FastBuf;
import io.edap.mqtt.ControlPacket;
import io.edap.mqtt.ControlPacketType;
import io.edap.mqtt.ParseContext;
import io.edap.mqtt.ProtocolLevel;
import io.edap.mqtt.decoder.ConnectDecoder;
import io.edap.mqtt.packet.Connect;
import io.edap.mqtt.test.MqttNioSessionV31;
import io.edap.mqtt.test.MqttNioSessionV311;
import io.edap.nio.ParseResult;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Random;

import static io.edap.mqtt.test.TestUtil.randomStr;
import static io.edap.mqtt.test.TestUtil.writeMqttVarInt;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class ConnectDecoderTest {

    @Test
    public void testParse() {
        ParseResult<ControlPacket> r;
        ConnectDecoder decoder = new ConnectDecoder();
        FastBuf buf = new FastBuf(4096);
        int fixHeader = (ControlPacketType.CONNECT_VALUE << 4);

        int len = 127;
        buf.write((byte) fixHeader);
        writeMqttVarInt(buf, len);
        ParseContext parseCtx = new ParseContext();
        parseCtx.setSession(new MqttNioSessionV31());
        parseCtx.setResult(new ParseResult<>());
        parseCtx.setRpos(buf.rpos());
        parseCtx.setParseData(new byte[8]);
        r = decoder.parse(buf, fixHeader, parseCtx);
        assertEquals(r.isFinished(), false);

        buf.wpos(parseCtx.getRpos());
        r = decoder.parse(buf, fixHeader, parseCtx);
        assertEquals(r.isFinished(), false);

        String protocolName = "MQIsdp";
        int protocolNameLen = protocolName.length();
        byte version = 3;
        int usernameFlag = 1;
        int passwordFlag = 1;
        int willRetain = 0;
        int qos = 1;
        int willFlag = 1;
        int clearSession = 1;
        int conFlag = usernameFlag << 7 | passwordFlag << 6 | willRetain << 5 | qos << 3 | willFlag << 2 | clearSession << 1;
        int keepAlive = 2049;
        String clientIdentifer = randomStr(1 + new Random().nextInt(23));
        String topic = randomStr(1 + new Random().nextInt(50));
        String message = randomStr(1 + new Random().nextInt(100));
        String username = randomStr(1 + new Random().nextInt(40));
        String password = randomStr(1 + new Random().nextInt(60));
        len = 12 +
                clientIdentifer.getBytes(StandardCharsets.UTF_8).length + 2 +
                topic.getBytes(StandardCharsets.UTF_8).length + 2 +
                message.getBytes(StandardCharsets.UTF_8).length + 2 +
                username.getBytes(StandardCharsets.UTF_8).length + 2 +
                password.getBytes(StandardCharsets.UTF_8).length;

        buf.reset();
        buf.write((byte) fixHeader);
        writeMqttVarInt(buf, len);
        buf.write((byte)((protocolNameLen >> 8) & 0xFF));
        buf.write((byte)(protocolNameLen & 0xFF));
        buf.write(protocolName.getBytes(StandardCharsets.UTF_8));
        buf.write(version);
        buf.write((byte)conFlag);
        buf.write((byte)((keepAlive >> 8) & 0xFF));
        buf.write((byte)(keepAlive & 0xFF));
        int ciLen = clientIdentifer.getBytes(StandardCharsets.UTF_8).length;
        int topicLen = topic.getBytes(StandardCharsets.UTF_8).length;
        int msgLen = message.getBytes(StandardCharsets.UTF_8).length;
        int unLen = username.getBytes(StandardCharsets.UTF_8).length;
        int pwdLen = password.getBytes(StandardCharsets.UTF_8).length;
        buf.write((byte)((ciLen >> 8) & 0xFF));
        buf.write((byte)(ciLen & 0xFF));
        buf.write(clientIdentifer.getBytes(StandardCharsets.UTF_8));
        buf.write((byte)((topicLen >> 8) & 0xFF));
        buf.write((byte)(topicLen & 0xFF));
        buf.write(topic.getBytes(StandardCharsets.UTF_8));
        buf.write((byte)((msgLen >> 8) & 0xFF));
        buf.write((byte)(msgLen & 0xFF));
        buf.write(message.getBytes(StandardCharsets.UTF_8));
        buf.write((byte)((unLen >> 8) & 0xFF));
        buf.write((byte)(unLen & 0xFF));
        buf.write(username.getBytes(StandardCharsets.UTF_8));
        buf.write((byte)((pwdLen >> 8) & 0xFF));
        buf.write((byte)(pwdLen & 0xFF));
        buf.write(password.getBytes(StandardCharsets.UTF_8));

        parseCtx.setRpos(buf.rpos() + 1);
        parseCtx.setParseData(new byte[2]);
        r = decoder.parse(buf, fixHeader, parseCtx);
        assertEquals(r.isFinished(), true);
        ControlPacket cp = r.getMessage();
        assertEquals(cp instanceof Connect, true);
        Connect connect = (Connect) cp;
        assertEquals(connect.getLowFourBits(), 0);
        assertEquals(connect.getWillFlag(), willFlag);
        assertEquals(connect.getProtocolLevel(), ProtocolLevel.VERSION_3_1);
        assertEquals(connect.getUserNameFlag(), 1);
        assertEquals(connect.getUserName(), username);
        assertEquals(connect.getPasswordFlag(), 1);
        assertEquals(connect.getPassword(), password);
        assertEquals(connect.getWillTopic(), topic);
        assertArrayEquals(connect.getWillPayload(), message.getBytes(StandardCharsets.UTF_8));
        assertEquals(connect.getCleanSessionFlag(), clearSession);
        assertEquals(connect.getKeepAlive(), keepAlive);

        protocolName = "MQTT";
        protocolNameLen = protocolName.length();
        version = 4;
        usernameFlag = 1;
        passwordFlag = 1;
        willRetain = 0;
        qos = 1;
        willFlag = 1;
        clearSession = 1;
        conFlag = usernameFlag << 7 | passwordFlag << 6 | willRetain << 5 | qos << 3 | willFlag << 2 | clearSession << 1;
        keepAlive = 2050;
        clientIdentifer = randomStr(1 + new Random().nextInt(23));
        topic = randomStr(50 + new Random().nextInt(3));
        message = randomStr(100 + new Random().nextInt(20));
        username = randomStr(300 + new Random().nextInt(50));
        password = randomStr(800 + new Random().nextInt(100));
        len = 12 +
                clientIdentifer.getBytes(StandardCharsets.UTF_8).length + 2 +
                topic.getBytes(StandardCharsets.UTF_8).length + 2 +
                message.getBytes(StandardCharsets.UTF_8).length + 2 +
                username.getBytes(StandardCharsets.UTF_8).length + 2 +
                password.getBytes(StandardCharsets.UTF_8).length;

        buf.reset();
        buf.write((byte) fixHeader);
        writeMqttVarInt(buf, len);
        buf.write((byte)((protocolNameLen >> 8) & 0xFF));
        buf.write((byte)(protocolNameLen & 0xFF));
        buf.write(protocolName.getBytes(StandardCharsets.UTF_8));
        buf.write(version);
        buf.write((byte)conFlag);
        buf.write((byte)((keepAlive >> 8) & 0xFF));
        buf.write((byte)(keepAlive & 0xFF));
        ciLen = clientIdentifer.getBytes(StandardCharsets.UTF_8).length;
        topicLen = topic.getBytes(StandardCharsets.UTF_8).length;
        msgLen = message.getBytes(StandardCharsets.UTF_8).length;
        unLen = username.getBytes(StandardCharsets.UTF_8).length;
        pwdLen = password.getBytes(StandardCharsets.UTF_8).length;
        buf.write((byte)((ciLen >> 8) & 0xFF));
        buf.write((byte)(ciLen & 0xFF));
        buf.write(clientIdentifer.getBytes(StandardCharsets.UTF_8));
        buf.write((byte)((topicLen >> 8) & 0xFF));
        buf.write((byte)(topicLen & 0xFF));
        buf.write(topic.getBytes(StandardCharsets.UTF_8));
        buf.write((byte)((msgLen >> 8) & 0xFF));
        buf.write((byte)(msgLen & 0xFF));
        buf.write(message.getBytes(StandardCharsets.UTF_8));
        buf.write((byte)((unLen >> 8) & 0xFF));
        buf.write((byte)(unLen & 0xFF));
        buf.write(username.getBytes(StandardCharsets.UTF_8));
        buf.write((byte)((pwdLen >> 8) & 0xFF));
        buf.write((byte)(pwdLen & 0xFF));
        buf.write(password.getBytes(StandardCharsets.UTF_8));

        parseCtx.setRpos(buf.rpos() + 1);
        parseCtx.setParseData(new byte[2]);
        r = decoder.parse(buf, fixHeader, parseCtx);
        assertEquals(r.isFinished(), true);
        cp = r.getMessage();
        assertEquals(cp instanceof Connect, true);
        connect = (Connect) cp;
        assertEquals(connect.getLowFourBits(), 0);
        assertEquals(connect.getWillFlag(), willFlag);
        assertEquals(connect.getProtocolLevel(), ProtocolLevel.VERSION_3_1_1);
        assertEquals(connect.getUserNameFlag(), 1);
        assertEquals(connect.getUserName(), username);
        assertEquals(connect.getPasswordFlag(), 1);
        assertEquals(connect.getPassword(), password);
        assertEquals(connect.getWillTopic(), topic);
        assertArrayEquals(connect.getWillPayload(), message.getBytes(StandardCharsets.UTF_8));
        assertEquals(connect.getKeepAlive(), keepAlive);

        protocolName = "MQTT";
        protocolNameLen = protocolName.length();
        version = 5;
        usernameFlag = 1;
        passwordFlag = 1;
        willRetain = 0;
        qos = 1;
        willFlag = 1;
        clearSession = 1;
        conFlag = usernameFlag << 7 | passwordFlag << 6 | willRetain << 5 | qos << 3 | willFlag << 2 | clearSession << 1;
        keepAlive = 2050;
        clientIdentifer = randomStr(20);
        topic = randomStr(50 + new Random().nextInt(3));
        message = randomStr(100 + new Random().nextInt(20));
        username = randomStr(300 + new Random().nextInt(50));
        password = randomStr(800 + new Random().nextInt(100));

        int sessionExpiryInterval = Short.MAX_VALUE + new Random().nextInt(Short.MAX_VALUE);
        int receiveMax = new Random().nextInt(Short.MAX_VALUE);
        int maxPacketSize = new Random().nextInt(Short.MAX_VALUE) + Short.MAX_VALUE;
        int topicAliasMax = new Random().nextInt(Short.MAX_VALUE);
        int requestRespInfo = 1;
        int requestProblemInfo = 0;
        String key = "username";
        String val = "root";
        String authMethod = "AES-256";
        byte[] authData = randomStr(50 + new Random().nextInt(300))
                .getBytes(StandardCharsets.UTF_8);

        int willDelayInteval = new Random().nextInt(Integer.MAX_VALUE);
        int payloadFormatIndicator  = 1;
        int msgExpiryInterval = new Random().nextInt(Integer.MAX_VALUE);
        String contentType = "application/json";
        String responseTopic = randomStr(5 + new Random().nextInt(10));
        byte[] correlationData = randomStr(10 + new Random().nextInt(50)).getBytes(StandardCharsets.UTF_8);
        String willKey = "nickName";
        String willVal = randomStr(5 + new Random().nextInt(20));

        len = 12 +
                clientIdentifer.getBytes(StandardCharsets.UTF_8).length + 2 +
                topic.getBytes(StandardCharsets.UTF_8).length + 2 +
                message.getBytes(StandardCharsets.UTF_8).length + 2 +
                username.getBytes(StandardCharsets.UTF_8).length + 2 +
                password.getBytes(StandardCharsets.UTF_8).length;
        int connPropsLen = 5 + 3 + 5 + 3 + 2 + 2 +
                3 + key.getBytes(StandardCharsets.UTF_8).length +
                2 + val.getBytes(StandardCharsets.UTF_8).length +
                3 + authMethod.getBytes(StandardCharsets.UTF_8).length +
                3 + authData.length;
        int willPropLen = 5 + 2 + 5 + 3 + contentType.getBytes(StandardCharsets.UTF_8).length +
                3 + responseTopic.getBytes(StandardCharsets.UTF_8).length +
                3 + correlationData.length +
                3 + willKey.getBytes(StandardCharsets.UTF_8).length +
                2 + willVal.getBytes(StandardCharsets.UTF_8).length;
        len += connPropsLen + willPropLen;

        buf.reset();
        buf.write((byte) fixHeader);
        writeMqttVarInt(buf, len);
        buf.write((byte)((protocolNameLen >> 8) & 0xFF));
        buf.write((byte)(protocolNameLen & 0xFF));
        buf.write(protocolName.getBytes(StandardCharsets.UTF_8));
        buf.write(version);
        buf.write((byte)conFlag);
        buf.write((byte)((keepAlive >> 8) & 0xFF));
        buf.write((byte)(keepAlive & 0xFF));

        writeMqttVarInt(buf, connPropsLen);
        long connPropStart = buf.wpos();
        buf.write((byte)17);
        buf.write((byte)((sessionExpiryInterval >> 24) & 0xFF));
        buf.write((byte)((sessionExpiryInterval >> 16) & 0xFF));
        buf.write((byte)((sessionExpiryInterval >> 8) & 0xFF));
        buf.write((byte)(sessionExpiryInterval & 0xFF));
        buf.write((byte)33);
        buf.write((byte)((receiveMax >> 8) & 0xFF));
        buf.write((byte)(receiveMax & 0xFF));
        buf.write((byte)39);
        buf.write((byte)((maxPacketSize >> 24) & 0xFF));
        buf.write((byte)((maxPacketSize >> 16) & 0xFF));
        buf.write((byte)((maxPacketSize >> 8) & 0xFF));
        buf.write((byte)(maxPacketSize & 0xFF));
        buf.write((byte)34);
        buf.write((byte)((topicAliasMax >> 8) & 0xFF));
        buf.write((byte)(topicAliasMax & 0xFF));
        buf.write((byte)25);
        buf.write((byte)requestRespInfo);
        buf.write((byte)23);
        buf.write((byte)requestProblemInfo);
        buf.write((byte)38);
        int keyLen = key.getBytes(StandardCharsets.UTF_8).length;
        buf.write((byte)((keyLen >> 8) & 0xFF));
        buf.write((byte)(keyLen & 0xFF));
        buf.write(key.getBytes(StandardCharsets.UTF_8));
        int valLen = val.getBytes(StandardCharsets.UTF_8).length;
        buf.write((byte)((valLen >> 8) & 0xFF));
        buf.write((byte)(valLen & 0xFF));
        buf.write(val.getBytes(StandardCharsets.UTF_8));
        int authMethodLen = authMethod.getBytes(StandardCharsets.UTF_8).length;
        buf.write((byte)21);
        buf.write((byte)((authMethodLen >> 8) & 0xFF));
        buf.write((byte)(authMethodLen & 0xFF));
        buf.write(authMethod.getBytes(StandardCharsets.UTF_8));
        int authDataLen = authData.length;
        buf.write((byte)22);
        buf.write((byte)((authDataLen >> 8) & 0xFF));
        buf.write((byte)(authDataLen & 0xFF));
        buf.write(authData);
        assertEquals(buf.wpos() - connPropStart, connPropsLen);

        ciLen = clientIdentifer.getBytes(StandardCharsets.UTF_8).length;
        topicLen = topic.getBytes(StandardCharsets.UTF_8).length;
        msgLen = message.getBytes(StandardCharsets.UTF_8).length;
        unLen = username.getBytes(StandardCharsets.UTF_8).length;
        pwdLen = password.getBytes(StandardCharsets.UTF_8).length;
        buf.write((byte)((ciLen >> 8) & 0xFF));
        buf.write((byte)(ciLen & 0xFF));
        buf.write(clientIdentifer.getBytes(StandardCharsets.UTF_8));

//        int willPropLen = 5 + 2 + 5 + 3 + contentType.getBytes(StandardCharsets.UTF_8).length +
//                3 + responseTopic.getBytes(StandardCharsets.UTF_8).length +
//                3 + correlationData.length +
//                3 + willKey.getBytes(StandardCharsets.UTF_8).length +
//                2 + willVal.getBytes(StandardCharsets.UTF_8).length;
        writeMqttVarInt(buf, willPropLen);
        long willPropStart = buf.wpos();
        buf.write((byte)24);
        buf.write((byte)((willDelayInteval >> 24) & 0xFF));
        buf.write((byte)((willDelayInteval >> 16) & 0xFF));
        buf.write((byte)((willDelayInteval >> 8) & 0xFF));
        buf.write((byte)(willDelayInteval & 0xFF));
        buf.write((byte)1);
        buf.write((byte)payloadFormatIndicator);
        buf.write((byte)2);
        buf.write((byte)((msgExpiryInterval >> 24) & 0xFF));
        buf.write((byte)((msgExpiryInterval >> 16) & 0xFF));
        buf.write((byte)((msgExpiryInterval >> 8) & 0xFF));
        buf.write((byte)(msgExpiryInterval & 0xFF));
        buf.write((byte)3);
        int contentTypeLen = contentType.getBytes(StandardCharsets.UTF_8).length;
        buf.write((byte)((contentTypeLen >> 8) & 0xFF));
        buf.write((byte)(contentTypeLen & 0xFF));
        buf.write(contentType.getBytes(StandardCharsets.UTF_8));
        buf.write((byte)8);
        int responseTopicLen = responseTopic.getBytes(StandardCharsets.UTF_8).length;
        buf.write((byte)((responseTopicLen >> 8) & 0xFF));
        buf.write((byte)(responseTopicLen & 0xFF));
        buf.write(responseTopic.getBytes(StandardCharsets.UTF_8));
        int corDataLen = correlationData.length;
        buf.write((byte)9);
        buf.write((byte)((corDataLen >> 8) & 0xFF));
        buf.write((byte)(corDataLen & 0xFF));
        buf.write(correlationData);
        int willKeyLen = willKey.getBytes(StandardCharsets.UTF_8).length;
        buf.write((byte)38);
        buf.write((byte)((willKeyLen >> 8) & 0xFF));
        buf.write((byte)(willKeyLen & 0xFF));
        buf.write(willKey.getBytes(StandardCharsets.UTF_8));
        int willValLen = willVal.getBytes(StandardCharsets.UTF_8).length;
        buf.write((byte)((willValLen >> 8) & 0xFF));
        buf.write((byte)(willValLen & 0xFF));
        buf.write(willVal.getBytes(StandardCharsets.UTF_8));
        assertEquals(buf.wpos() - willPropStart, willPropLen);

        buf.write((byte)((topicLen >> 8) & 0xFF));
        buf.write((byte)(topicLen & 0xFF));
        buf.write(topic.getBytes(StandardCharsets.UTF_8));
        buf.write((byte)((msgLen >> 8) & 0xFF));
        buf.write((byte)(msgLen & 0xFF));
        buf.write(message.getBytes(StandardCharsets.UTF_8));
        buf.write((byte)((unLen >> 8) & 0xFF));
        buf.write((byte)(unLen & 0xFF));
        buf.write(username.getBytes(StandardCharsets.UTF_8));
        buf.write((byte)((pwdLen >> 8) & 0xFF));
        buf.write((byte)(pwdLen & 0xFF));
        buf.write(password.getBytes(StandardCharsets.UTF_8));

        parseCtx.setRpos(buf.rpos() + 1);
        r = decoder.parse(buf, fixHeader, parseCtx);
        assertEquals(r.isFinished(), true);
        assertEquals(buf.wpos(), buf.rpos());
        cp = r.getMessage();
        assertEquals(cp instanceof Connect, true);
        connect = (Connect) cp;
        assertEquals(connect.getLowFourBits(), 0);
        assertEquals(connect.getProtocolName(), protocolName);
        assertEquals(connect.getProtocolLevel(), ProtocolLevel.VERSION_5);
        assertEquals(connect.getWillFlag(), willFlag);
        assertEquals(connect.getUserNameFlag(), usernameFlag);
        assertEquals(connect.getPasswordFlag(), passwordFlag);
        assertEquals(connect.getWillRetain(), willRetain);
        assertEquals(connect.getWillQoS(), qos);
        assertEquals(connect.getCleanSessionFlag(), clearSession);
        assertEquals(connect.getKeepAlive(), keepAlive);
        assertEquals(connect.getConnProperties().size(), 9);
        assertEquals(connect.getUserName(), username);

        assertEquals(connect.getPassword(), password);
        assertEquals(connect.getWillTopic(), topic);
        assertArrayEquals(connect.getWillPayload(), message.getBytes(StandardCharsets.UTF_8));



        len = 2097152;
        buf.reset();
        buf.write((byte) fixHeader);
        writeMqttVarInt(buf, len);
        parseCtx = new ParseContext();
        parseCtx.setSession(new MqttNioSessionV31());
        parseCtx.setResult(new ParseResult<>());
        parseCtx.setRpos(buf.rpos() + 1);
        parseCtx.setParseData(new byte[4]);
        buf.wpos(buf.wpos() - 2);
        r = decoder.parse(buf, fixHeader, parseCtx);
        assertEquals(r.isFinished(), false);

    }
}
