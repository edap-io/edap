package io.edap.mqtt.utils;

import io.edap.mqtt.*;
import io.edap.mqtt.packet.*;
import io.edap.util.CollectionUtils;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static io.edap.mqtt.ControlPacketType.PUBACK;

public class EncoderUtils {

    static final int PROTOCOL_NAME_LENGTH     = 4;
    static final int PROTOCOL_V31_NAME_LENGTH = 6;
    static final byte[] PROTOCAL_V31_NAME_DATA = new byte[]{'M', 'Q', 'I', 's', 'd', 'p'};

    private EncoderUtils() {}

    public static void encodeConnectData(MqttWriter writer, Connect connect) {
        if (connect.getProtocolLevel() == ProtocolLevel.VERSION_3_1) {
            writer.writeBytes((byte)0, (byte) (PROTOCOL_V31_NAME_LENGTH & 0xFF));
            writer.writeByteArray(PROTOCAL_V31_NAME_DATA);
        } else {
            writer.writeBytes((byte)0, (byte) (PROTOCOL_NAME_LENGTH & 0xFF));
            writer.writeBytes((byte) 'M', (byte) 'Q', (byte) 'T', (byte) 'T');
        }
        int connFlag = connect.getUserNameFlag() << 7 |
                connect.getPasswordFlag()     << 6 |
                connect.getWillRetain()       << 5 |
                connect.getWillQoS()          << 3 |
                connect.getWillFlag()         << 2 |
                connect.getCleanSessionFlag() << 1;
        int keepAlive = connect.getKeepAlive();
        int protocolLevelValue = connect.getProtocolLevel().getValue();
        writer.writeBytes((byte)protocolLevelValue,
                (byte)connFlag,
                (byte)(keepAlive >> 8),
                (byte)(keepAlive & 0xFF));
        LinkedHashMap<PropertyType, PacketProperty> connProps = connect.getConnProperties();
        if (protocolLevelValue > 4 && !CollectionUtils.isEmpty(connProps)) {
            encodeProperties(writer, connProps);
        }
        byte[] clientIdData = connect.getClientIdentifier().getBytes(StandardCharsets.UTF_8);
        int clientIdLen = clientIdData.length;
        writer.writeBytes((byte)(clientIdLen >> 8), (byte)(clientIdLen & 0xFF));
        writer.writeByteArray(clientIdData);
        LinkedHashMap<PropertyType, PacketProperty> props = connect.getProperties();
        if (protocolLevelValue > 4 && !CollectionUtils.isEmpty(props)) {
            encodeProperties(writer, props);
        }
        if (connect.getWillFlag() == 1) {
            writer.writeString(connect.getWillTopic());
            writer.write(connect.getWillPayload());
        }
        if (connect.getUserNameFlag() == 1) {
            writer.writeString(connect.getUserName());
        }
        if (connect.getPasswordFlag() == 1) {
            writer.writeString(connect.getPassword());
        }
    }

    public static void encodeConnAckData(MqttWriter writer, ConnAck connAck) {
        writer.writeBytes((byte) connAck.getConnAckFlag(), (byte) connAck.getConnAckCode());
        LinkedHashMap<PropertyType, PacketProperty> props = connAck.getProperties();
        if (!CollectionUtils.isEmpty(props)) {
            encodeProperties(writer, props);
        }
    }

    public static void encodePublishData(MqttWriter writer, Publish publish, ProtocolLevel level) {
        writer.writeString(publish.getTopic());
        int id = publish.getPacketIdentifier();
        writer.writeBytes((byte)(id >> 8), (byte)(id & 0xFF));
        if (level.getValue() > ProtocolLevel.VERSION_3_1_1.getValue()) {
            encodeProperties(writer, publish.getProperties());
        }
        writer.write(publish.getPayload());
    }

    public static void encodePubAckData(MqttWriter writer, PubAck pubAck, ProtocolLevel level) {
        int pi = pubAck.getPacketIdentifier();
        if (level.getValue() < ProtocolLevel.VERSION_5.getValue()) {
            writer.writeBytes((byte) (pi >> 8), (byte) (pi & 0xFF));
            return;
        }
        writer.writeBytes((byte)(pi >> 8), (byte)(pi & 0xFF));
        writer.writeByte((byte)(pubAck.getReasonCode() & 0xFF));
        LinkedHashMap<PropertyType, PacketProperty> props = pubAck.getProperties();
        if (!CollectionUtils.isEmpty(props)) {
            encodeProperties(writer, props);
        }
    }

    public static void encodePubRecData(MqttWriter writer, PubRec pubRec, ProtocolLevel level) {
        int pi = pubRec.getPacketIdentifier();
        if (level.getValue() < ProtocolLevel.VERSION_5.getValue()) {
            writer.writeBytes((byte) (pi >> 8), (byte) (pi & 0xFF));
            return;
        }
        writer.writeBytes((byte)(pi >> 8), (byte)(pi & 0xFF));
        writer.writeByte((byte)(pubRec.getReasonCode() & 0xFF));
        LinkedHashMap<PropertyType, PacketProperty> props = pubRec.getProperties();
        if (!CollectionUtils.isEmpty(props)) {
            encodeProperties(writer, props);
        }
    }

    public static void encodePubRelData(MqttWriter writer, PubRel pubRel, ProtocolLevel level) {
        int pi = pubRel.getPacketIdentifier();
        if (level.getValue() < ProtocolLevel.VERSION_5.getValue()) {
            writer.writeBytes((byte) (pi >> 8), (byte) (pi & 0xFF));
            return;
        }
        writer.writeBytes((byte)(pi >> 8), (byte)(pi & 0xFF));
        writer.writeByte((byte)(pubRel.getReasonCode() & 0xFF));
        LinkedHashMap<PropertyType, PacketProperty> props = pubRel.getProperties();
        if (!CollectionUtils.isEmpty(props)) {
            encodeProperties(writer, props);
        }
    }

    public static void encodePubCompData(MqttWriter writer, PubComp pubComp, ProtocolLevel level) {
        int pi = pubComp.getPacketIdentifier();
        if (level.getValue() < ProtocolLevel.VERSION_5.getValue()) {
            writer.writeBytes((byte) (pi >> 8), (byte) (pi & 0xFF));
            return;
        }
        writer.writeBytes((byte)(pi >> 8), (byte)(pi & 0xFF));
        writer.writeByte((byte)(pubComp.getReasonCode() & 0xFF));
        LinkedHashMap<PropertyType, PacketProperty> props = pubComp.getProperties();
        if (!CollectionUtils.isEmpty(props)) {
            encodeProperties(writer, props);
        }
    }

    public static void encodeSubscribeData(MqttWriter writer, Subscribe subscribe, ProtocolLevel level) {
        int pi = subscribe.getPacketIdentifier();
        writer.writeBytes((byte)(pi >> 8), (byte)(pi & 0xFF));
        if (level.getValue() > ProtocolLevel.VERSION_3_1_1.getValue()) {
            encodeProperties(writer, subscribe.getProperties());
        }
        List<TopicFilter> topics = subscribe.getTopicFilterList();
        if (!CollectionUtils.isEmpty(topics)) {
            for (TopicFilter topic : topics) {
                writer.writeString(topic.getTopicFilter());
                writer.writeByte((byte)topic.getSubscriptionOptions());
            }
        }
    }

    public static void encodeSubAckData(MqttWriter writer, SubAck subAck, ProtocolLevel level) {
        int pi = subAck.getPacketIdentifier();
        writer.writeBytes((byte)(pi >> 8), (byte)(pi & 0xFF));
        if (level.getValue() > ProtocolLevel.VERSION_3_1_1.getValue()) {
            encodeProperties(writer, subAck.getProperties());
        }
        List<Integer> codes = subAck.getRespCodes();
        if (!CollectionUtils.isEmpty(codes)) {
            for (Integer code : codes) {
                writer.writeByte(code.byteValue());
            }
        }
    }

    public static void encodeUnsubscribeData(MqttWriter writer, Unsubscribe unsubscribe, ProtocolLevel level) {
        int pi = unsubscribe.getPacketIdentifier();
        writer.writeBytes((byte)(pi >> 8), (byte)(pi & 0xFF));
        if (level.getValue() > ProtocolLevel.VERSION_3_1_1.getValue()) {
            encodeProperties(writer, unsubscribe.getProperties());
        }
        List<String> codes = unsubscribe.getTopicFilterList();
        if (!CollectionUtils.isEmpty(codes)) {
            for (String topic : codes) {
                writer.writeString(topic);
            }
        }
    }

    public static void encodeUnsubAckData(MqttWriter writer, UnsubAck unsubAck, ProtocolLevel level) {
        int pi = unsubAck.getPacketIdentifier();
        writer.writeBytes((byte)(pi >> 8), (byte)(pi & 0xFF));
        if (level.getValue() > ProtocolLevel.VERSION_3_1_1.getValue()) {
            encodeProperties(writer, unsubAck.getProperties());
            List<Integer> codes = unsubAck.getReasonCodes();
            for (Integer code : codes) {
                writer.writeByte(code.byteValue());
            }
        }
    }

    public static void encodeDisconnectData(MqttWriter writer, Disconnect disconnect, ProtocolLevel level) {
        if (level.getValue() > ProtocolLevel.VERSION_3_1_1.getValue()) {
            writer.writeByte((byte)disconnect.getReasonCode());
            encodeProperties(writer, disconnect.getProperties());
        }
    }

    public static void encodeAuthData(MqttWriter writer, Auth auth, ProtocolLevel level) {
        writer.writeByte((byte)auth.getReasonCode());
        encodeProperties(writer, auth.getProperties());
    }

    public static void encodeProperties(MqttWriter writer, LinkedHashMap<PropertyType, PacketProperty> props) {
        int oldPos = writer.getPos();
        writer.setPos(oldPos + 1);
        for (Map.Entry<PropertyType, PacketProperty> entry : props.entrySet()) {
            writer.writeByte((byte)entry.getKey().getType());
            entry.getValue().writeTo(writer);
        }
        int len = writer.getPos() - oldPos - 1;
        writer.checkMoveAndWriteLen(oldPos, len);
    }


}
