package io.edap.mqtt;

import io.edap.mqtt.packet.*;

import static io.edap.mqtt.ControlPacketType.*;
import static io.edap.mqtt.utils.EncoderUtils.*;

public interface MqttEncoder {

    ThreadLocal<MqttWriter> LOCAL_MQTT_WRITER =
            ThreadLocal.withInitial(() -> new MqttWriter());

    ProtocolLevel getProtocelLevel();

    default void encode(MqttWriter writer, Connect connect) {
        connect.setProtocolLevel(getProtocelLevel());
        if (writer.getStart() == 0) {
            writer.setStart(5);
            encodeConnectData(writer, connect);
            int len = writer.getPos() - 5;
            writer.writeLength((byte)(CONNECT_VALUE << 4), len);
        } else {
            writer.writeByte((byte)(CONNECT_VALUE << 4  | connect.getLowFourBits()));
            int oldPos = writer.getPos();
            writer.setPos(oldPos + 1);
            encodeConnectData(writer, connect);
            int len = writer.getPos() - oldPos - 1;
            writer.checkMoveAndWriteLen(oldPos, len);
        }
    }

    default void encode(MqttWriter writer, ConnAck connAck) {
        ProtocolLevel level = getProtocelLevel();
        if (level.getValue() < ProtocolLevel.VERSION_5.getValue()) {
            writer.writeBytes(
                    (byte) (CONNACK_VALUE << 4 | connAck.getLowFourBits()),
                    (byte) 2,
                    (byte) connAck.getConnAckFlag(),
                    (byte) connAck.getConnAckCode());
            return;
        }
        if (writer.getStart() == 0) {
            writer.setStart(5);
            encodeConnAckData(writer, connAck);
            int len = writer.getPos() - 5;
            writer.writeLength((byte)(CONNACK_VALUE << 4 | connAck.getLowFourBits()), len);
        } else {
            writer.writeByte((byte)(CONNACK_VALUE << 4  | connAck.getLowFourBits()));
            int oldPos = writer.getPos();
            writer.setPos(oldPos + 1);
            encodeConnAckData(writer, connAck);
            int len = writer.getPos() - oldPos - 1;
            writer.checkMoveAndWriteLen(oldPos, len);
        }
    }

    default void encode(MqttWriter writer, Publish publish) {
        int lowFourBits = (publish.getRetain() & 0x1);
        lowFourBits |= (publish.getQos().getValue() & 0x3) << 1;
        lowFourBits |= (publish.getDup() & 0x1) << 3;
        ProtocolLevel level = getProtocelLevel();
        if (writer.getStart() == 0) {
            writer.setStart(5);
            encodePublishData(writer, publish, level);
            int len = writer.getPos() - 5;
            writer.writeLength((byte)(PUBLISH_VALUE << 4 | lowFourBits), len);
        } else {
            writer.writeByte((byte)(PUBLISH_VALUE << 4 | lowFourBits));
            int oldPos = writer.getPos();
            writer.setPos(oldPos + 1);
            encodePublishData(writer, publish, level);
            int len = writer.getPos() - oldPos - 1;
            writer.checkMoveAndWriteLen(oldPos, len);
        }
    }

    default void encode(MqttWriter writer, PubAck pubAck) {
        int lowFourBits = (pubAck.getRetain() & 0x1);
        lowFourBits |= (pubAck.getQos().getValue() & 0x3) << 1;
        lowFourBits |= (pubAck.getDup() & 0x1) << 3;
        ProtocolLevel level = getProtocelLevel();
        if (writer.getStart() == 0) {
            writer.setStart(5);
            encodePubAckData(writer, pubAck, level);
            int len = writer.getPos() - 5;
            writer.writeLength((byte)(PUBACK_VALUE << 4 | lowFourBits), len);
        } else {
            writer.writeByte((byte)(PUBACK_VALUE << 4 | lowFourBits));
            int oldPos = writer.getPos();
            writer.setPos(oldPos + 1);
            encodePubAckData(writer, pubAck, level);
            int len = writer.getPos() - oldPos - 1;
            writer.checkMoveAndWriteLen(oldPos, len);
        }
    }

    default void encode(MqttWriter writer, PubRec pubRec) {
        int lowFourBits = (pubRec.getRetain() & 0x1);
        lowFourBits |= (pubRec.getQos().getValue() & 0x3) << 1;
        lowFourBits |= (pubRec.getDup() & 0x1) << 3;
        ProtocolLevel level = getProtocelLevel();
        if (writer.getStart() == 0) {
            writer.setStart(5);
            encodePubRecData(writer, pubRec, level);
            int len = writer.getPos() - 5;
            writer.writeLength((byte)(PUBREC_VALUE << 4 | lowFourBits), len);
        } else {
            writer.writeByte((byte)(PUBREC_VALUE << 4 | lowFourBits));
            int oldPos = writer.getPos();
            writer.setPos(oldPos + 1);
            encodePubRecData(writer, pubRec, level);
            int len = writer.getPos() - oldPos - 1;
            writer.checkMoveAndWriteLen(oldPos, len);
        }
    }

    default void encode(MqttWriter writer, PubRel pubRel) {
        int lowFourBits = (pubRel.getRetain() & 0x1);
        lowFourBits |= (pubRel.getQos().getValue() & 0x3) << 1;
        lowFourBits |= (pubRel.getDup() & 0x1) << 3;
        ProtocolLevel level = getProtocelLevel();
        if (writer.getStart() == 0) {
            writer.setStart(5);
            encodePubRelData(writer, pubRel, level);
            int len = writer.getPos() - 5;
            writer.writeLength((byte)(PUBREL_VALUE << 4 | lowFourBits), len);
        } else {
            writer.writeByte((byte)(PUBREL_VALUE << 4 | lowFourBits));
            int oldPos = writer.getPos();
            writer.setPos(oldPos + 1);
            encodePubRelData(writer, pubRel, level);
            int len = writer.getPos() - oldPos - 1;
            writer.checkMoveAndWriteLen(oldPos, len);
        }
    }

    default void encode(MqttWriter writer, PubComp pubComp) {
        int lowFourBits = (pubComp.getRetain() & 0x1);
        lowFourBits |= (pubComp.getQos().getValue() & 0x3) << 1;
        lowFourBits |= (pubComp.getDup() & 0x1) << 3;
        ProtocolLevel level = getProtocelLevel();
        if (writer.getStart() == 0) {
            writer.setStart(5);
            encodePubCompData(writer, pubComp, level);
            int len = writer.getPos() - 5;
            writer.writeLength((byte)(PUBCOMP_VALUE << 4 | lowFourBits), len);
        } else {
            writer.writeByte((byte)(PUBCOMP_VALUE << 4 | lowFourBits));
            int oldPos = writer.getPos();
            writer.setPos(oldPos + 1);
            encodePubCompData(writer, pubComp, level);
            int len = writer.getPos() - oldPos - 1;
            writer.checkMoveAndWriteLen(oldPos, len);
        }
    }

    default void encode(MqttWriter writer, Subscribe subscribe) {
        int lowFourBits = (subscribe.getRetain() & 0x1);
        lowFourBits |= (subscribe.getQos().getValue() & 0x3) << 1;
        lowFourBits |= (subscribe.getDup() & 0x1) << 3;
        ProtocolLevel level = getProtocelLevel();
        if (writer.getStart() == 0) {
            writer.setStart(5);
            encodeSubscribeData(writer, subscribe, level);
            int len = writer.getPos() - 5;
            writer.writeLength((byte)(SUBSCRIBE_VALUE << 4 | lowFourBits), len);
        } else {
            writer.writeByte((byte)(SUBSCRIBE_VALUE << 4 | lowFourBits));
            int oldPos = writer.getPos();
            writer.setPos(oldPos + 1);
            encodeSubscribeData(writer, subscribe, level);
            int len = writer.getPos() - oldPos - 1;
            writer.checkMoveAndWriteLen(oldPos, len);
        }
    }

    default void encode(MqttWriter writer, SubAck subAck) {
        int lowFourBits = (subAck.getRetain() & 0x1);
        lowFourBits |= (subAck.getQos().getValue() & 0x3) << 1;
        lowFourBits |= (subAck.getDup() & 0x1) << 3;
        ProtocolLevel level = getProtocelLevel();
        if (writer.getStart() == 0) {
            writer.setStart(5);
            encodeSubAckData(writer, subAck, level);
            int len = writer.getPos() - 5;
            writer.writeLength((byte)(SUBACK_VALUE << 4 | lowFourBits), len);
        } else {
            writer.writeByte((byte)(SUBACK_VALUE << 4 | lowFourBits));
            int oldPos = writer.getPos();
            writer.setPos(oldPos + 1);
            encodeSubAckData(writer, subAck, level);
            int len = writer.getPos() - oldPos - 1;
            writer.checkMoveAndWriteLen(oldPos, len);
        }
    }

    default void encode(MqttWriter writer, Unsubscribe unsubscribe) {
        int lowFourBits = (unsubscribe.getRetain() & 0x1);
        lowFourBits |= (unsubscribe.getQos().getValue() & 0x3) << 1;
        lowFourBits |= (unsubscribe.getDup() & 0x1) << 3;
        ProtocolLevel level = getProtocelLevel();
        if (writer.getStart() == 0) {
            writer.setStart(5);
            encodeUnsubscribeData(writer, unsubscribe, level);
            int len = writer.getPos() - 5;
            writer.writeLength((byte)(UNSUBSCRIBE_VALUE << 4 | lowFourBits), len);
        } else {
            writer.writeByte((byte)(UNSUBSCRIBE_VALUE << 4 | lowFourBits));
            int oldPos = writer.getPos();
            writer.setPos(oldPos + 1);
            encodeUnsubscribeData(writer, unsubscribe, level);
            int len = writer.getPos() - oldPos - 1;
            writer.checkMoveAndWriteLen(oldPos, len);
        }
    }

    default void encode(MqttWriter writer, UnsubAck unsubAck) {
        int lowFourBits = (unsubAck.getRetain() & 0x1);
        lowFourBits |= (unsubAck.getQos().getValue() & 0x3) << 1;
        lowFourBits |= (unsubAck.getDup() & 0x1) << 3;
        ProtocolLevel level = getProtocelLevel();
        if (writer.getStart() == 0) {
            writer.setStart(5);
            encodeUnsubAckData(writer, unsubAck, level);
            int len = writer.getPos() - 5;
            writer.writeLength((byte)(UNSUBACK_VALUE << 4 | lowFourBits), len);
        } else {
            writer.writeByte((byte)(UNSUBACK_VALUE << 4 | lowFourBits));
            int oldPos = writer.getPos();
            writer.setPos(oldPos + 1);
            encodeUnsubAckData(writer, unsubAck, level);
            int len = writer.getPos() - oldPos - 1;
            writer.checkMoveAndWriteLen(oldPos, len);
        }
    }

    default void encode(MqttWriter writer, PingReq pingReq) {
        writer.writeBytes((byte)(PINGREQ_VALUE << 4), (byte)0);
    }

    default void encode(MqttWriter writer, PingResp pingResp) {
        writer.writeBytes((byte)(PINGRESP_VALUE << 4), (byte)0);
    }

    default void encode(MqttWriter writer, Disconnect disconnect) {
        int lowFourBits = (disconnect.getRetain() & 0x1);
        lowFourBits |= (disconnect.getQos().getValue() & 0x3) << 1;
        lowFourBits |= (disconnect.getDup() & 0x1) << 3;
        ProtocolLevel level = getProtocelLevel();
        if (writer.getStart() == 0) {
            writer.setStart(5);
            encodeDisconnectData(writer, disconnect, level);
            int len = writer.getPos() - 5;
            writer.writeLength((byte)(DISCONNECT_VALUE << 4 | lowFourBits), len);
        } else {
            writer.writeByte((byte)(DISCONNECT_VALUE << 4 | lowFourBits));
            int oldPos = writer.getPos();
            writer.setPos(oldPos + 1);
            encodeDisconnectData(writer, disconnect, level);
            int len = writer.getPos() - oldPos - 1;
            writer.checkMoveAndWriteLen(oldPos, len);
        }
    }

    default void encode(MqttWriter writer, Auth auth) {
        ProtocolLevel level = getProtocelLevel();
        if (level.getValue() < ProtocolLevel.VERSION_5.getValue()) {
            throw new RuntimeException("Mqtt protocol level no supported!");
        }
        int lowFourBits = (auth.getRetain() & 0x1);
        lowFourBits |= (auth.getQos().getValue() & 0x3) << 1;
        lowFourBits |= (auth.getDup() & 0x1) << 3;
        if (writer.getStart() == 0) {
            writer.setStart(5);
            encodeAuthData(writer, auth, level);
            int len = writer.getPos() - 5;
            writer.writeLength((byte)(AUTH_VALUE << 4 | lowFourBits), len);
        } else {
            writer.writeByte((byte)(AUTH_VALUE << 4 | lowFourBits));
            int oldPos = writer.getPos();
            writer.setPos(oldPos + 1);
            encodeAuthData(writer, auth, level);
            int len = writer.getPos() - oldPos - 1;
            writer.checkMoveAndWriteLen(oldPos, len);
        }
    }

}
