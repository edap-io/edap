package io.edap.mqtt.broker;

import io.edap.buffer.FastBuf;
import io.edap.mqtt.*;
import io.edap.mqtt.packet.*;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

import static io.edap.mqtt.ControlPacketType.*;
import static io.edap.mqtt.MqttEncoder.LOCAL_MQTT_WRITER;
import static io.edap.nio.NioSession.THREAD_WRITE_BUF;

public interface MqttBrokerHandler {

    byte[] PING_RESP_DATA = new byte[]{(byte)(PINGRESP.getValue() << 4), 0};

    default void handleConnect(MqttBrokerSession session, Connect connect) throws IOException {
        ConnAck connAck = new ConnAck(0);
        byte ackFlag = 0;
        connAck.setConnAckCode(0);
        connAck.setConnAckFlag(ackFlag);
        if (connect.getProtocolLevel().getValue() > ProtocolLevel.VERSION_3_1_1.getValue()) {
            LinkedHashMap<PropertyType, PacketProperty> props = new LinkedHashMap<>();
            LinkedHashMap<PropertyType, PacketProperty> connProps = connect.getConnProperties();
            PacketProperty sei = connProps.get(PropertyType.SESSION_EXPIRY_INTERVAL);
            if (sei != null) {
                props.put(PropertyType.SESSION_EXPIRY_INTERVAL, sei);
            }
            PacketProperty rm = connProps.get(PropertyType.RECEIVE_MAXINUM);
            if (rm != null) {
                props.put(PropertyType.RECEIVE_MAXINUM, rm);
            }
            PacketProperty mqos = connProps.get(PropertyType.MAXIMUM_QOS);
            if (mqos != null) {
                props.put(PropertyType.MAXIMUM_QOS, mqos);
            }
            PacketProperty ra = connProps.get(PropertyType.RETAIN_AVAILABLE);
            if (ra != null) {
                props.put(PropertyType.RETAIN_AVAILABLE, ra);
            }
            PacketProperty mps = connProps.get(PropertyType.MAXIMUM_PACKET_SIZE);
            if (mps != null) {
                props.put(PropertyType.MAXIMUM_PACKET_SIZE, mps);
            }
            PacketProperty aci = connProps.get(PropertyType.ASSIGNED_CLIENT_IDENTIFIER);
            if (aci != null) {
                props.put(PropertyType.ASSIGNED_CLIENT_IDENTIFIER, aci);
            }
            PacketProperty tam = connProps.get(PropertyType.TOPIC_ALIAS_MAXIMUM);
            if (tam != null) {
                props.put(PropertyType.TOPIC_ALIAS_MAXIMUM, tam);
            }
            PacketProperty rs = connProps.get(PropertyType.REASON_STRING);
            if (rs != null) {
                props.put(PropertyType.REASON_STRING, rs);
            }
            PacketProperty up = connProps.get(PropertyType.USER_PROPERTY);
            if (up != null) {
                props.put(PropertyType.USER_PROPERTY, up);
            }
            PacketProperty wsa = connProps.get(PropertyType.WILDCARD_SUBSCRIPTION_AVAILABLE);
            if (wsa != null) {
                props.put(PropertyType.WILDCARD_SUBSCRIPTION_AVAILABLE, wsa);
            }
            PacketProperty sia = connProps.get(PropertyType.SUBSCRIPTION_INDENTIFIER_AVAILABLE);
            if (sia != null) {
                props.put(PropertyType.SUBSCRIPTION_INDENTIFIER_AVAILABLE, sia);
            }
            PacketProperty ssa = connProps.get(PropertyType.SHARED_SUBSCRIPTION_AVAILABLE);
            if (ssa != null) {
                props.put(PropertyType.SHARED_SUBSCRIPTION_AVAILABLE, ssa);
            }
            PacketProperty ska = connProps.get(PropertyType.SERVER_KEEP_ALIVE);
            if (ska != null) {
                props.put(PropertyType.SERVER_KEEP_ALIVE, ska);
            }
            PacketProperty ri = connProps.get(PropertyType.RESPONSE_INFORMATION);
            if (ri != null) {
                props.put(PropertyType.RESPONSE_INFORMATION, ri);
            }
            PacketProperty sr = connProps.get(PropertyType.SERVER_REFERENCE);
            if (sr != null) {
                props.put(PropertyType.SERVER_REFERENCE, sr);
            }
            PacketProperty am = connProps.get(PropertyType.AUTHENTICATION_METHOD);
            if (am != null) {
                props.put(PropertyType.AUTHENTICATION_METHOD, am);
            }
            PacketProperty ad = connProps.get(PropertyType.AUTHENTICATION_DATA);
            if (ad != null) {
                props.put(PropertyType.AUTHENTICATION_DATA, ad);
            }
            connAck.setProperties(props);
        }
        session.setProtocolLevel(connect.getProtocolLevel());
        session.setConnected(true);
        session.setClientId(connect.getClientIdentifier());

        MqttEncoder encoder = session.getMqttEncoder();
        if (encoder == null) {
            Disconnect disconnect = new Disconnect(DISCONNECT_VALUE << 4);
            disconnect.setReasonCode(ReasonCode.MALFORMED_PACKET.getCode());
            handleDisconnect(session, disconnect);
            return;
        }
        MqttWriter writer = LOCAL_MQTT_WRITER.get();
        FastBuf writeBuf = THREAD_WRITE_BUF.get();
        writer.reset();
        encoder.encode(writer, connAck);
        int len = writer.getLength();
        if (writeBuf.writeRemain() > len) {
            writeBuf.write(writer.getData(), writer.getStart(), len);
        }
        //session.fastWrite(writeBuf);
    }

    default void handlePublish(MqttBrokerSession session, Publish publish) {
        QoSLevel qoSLevel = publish.getQos();
        if (session.getQoSLevel().getValue() < qoSLevel.getValue()) {
            Disconnect disconnect = new Disconnect(DISCONNECT_VALUE << 4);
            disconnect.setReasonCode(ReasonCode.QOS_NOT_SUPPORTED.getCode());
            handleDisconnect(session, disconnect);
            return;
        }
        if (qoSLevel == QoSLevel.RESERVED) {
            Disconnect disconnect = new Disconnect(DISCONNECT_VALUE << 4);
            disconnect.setReasonCode(ReasonCode.MALFORMED_PACKET.getCode());
            handleDisconnect(session, disconnect);
            return;
        }
        SubscribeManager subManager = session.getSubscribeManager();
        subManager.checkAndAddTopic(publish.getTopic());
        if (qoSLevel == QoSLevel.LEAST_ONCE) {
            PubAck pubAck = new PubAck(PUBACK.getValue() << 4);
            pubAck.setPacketIdentifier(publish.getPacketIdentifier());
            MqttEncoder encoder = session.getMqttEncoder();
            if (encoder == null) {
                Disconnect disconnect = new Disconnect(DISCONNECT_VALUE << 4);
                disconnect.setReasonCode(ReasonCode.MALFORMED_PACKET.getCode());
                handleDisconnect(session, disconnect);
                return;
            }
            MqttWriter writer = LOCAL_MQTT_WRITER.get();
            FastBuf writeBuf = THREAD_WRITE_BUF.get();
            writer.reset();
            encoder.encode(writer, pubAck);
            int len = writer.getLength();
            if (writeBuf.writeRemain() > len) {
                writeBuf.write(writer.getData(), writer.getStart(), len);
            }
        } else if (qoSLevel == QoSLevel.EXACTLY_ONCE) {
            PubRec pubRec = new PubRec(PUBREC.getValue() << 4);
            pubRec.setPacketIdentifier(publish.getPacketIdentifier());
            MqttEncoder encoder = session.getMqttEncoder();
            if (encoder == null) {
                Disconnect disconnect = new Disconnect(DISCONNECT_VALUE << 4);
                disconnect.setReasonCode(ReasonCode.MALFORMED_PACKET.getCode());
                handleDisconnect(session, disconnect);
                return;
            }
            MqttWriter writer = LOCAL_MQTT_WRITER.get();
            FastBuf writeBuf = THREAD_WRITE_BUF.get();
            writer.reset();
            encoder.encode(writer, pubRec);
            int len = writer.getLength();
            if (writeBuf.writeRemain() > len) {
                writeBuf.write(writer.getData(), writer.getStart(), len);
            }
        }
    }

    default void handlePubAck(MqttNioSession session, PubAck pubAck) {

    }

    default void handlePubRec(MqttNioSession session, PubRec pubRec) {

    }

    default void handlePubRel(MqttNioSession session, PubRel pubRel) {
        PubComp pubComp = new PubComp(0);
        pubComp.setPacketIdentifier(pubRel.getPacketIdentifier());
        MqttEncoder encoder = session.getMqttEncoder();
        if (encoder == null) {
            Disconnect disconnect = new Disconnect(DISCONNECT_VALUE << 4);
            disconnect.setReasonCode(ReasonCode.MALFORMED_PACKET.getCode());
            handleDisconnect(session, disconnect);
            return;
        }
        MqttWriter writer = LOCAL_MQTT_WRITER.get();
        FastBuf writeBuf = THREAD_WRITE_BUF.get();
        writer.reset();
        encoder.encode(writer, pubComp);
        int len = writer.getLength();
        if (writeBuf.writeRemain() > len) {
            writeBuf.write(writer.getData(), writer.getStart(), len);
        }
    }

    default void handlePubComp(MqttNioSession session, PubComp pubComp) {

    }

    default void handleSubscribe(MqttBrokerSession session, Subscribe subscribe) {
        MqttEncoder encoder = session.getMqttEncoder();
        if (encoder == null) {
            Disconnect disconnect = new Disconnect(DISCONNECT_VALUE << 4);
            disconnect.setReasonCode(ReasonCode.NOT_AUTHORIZED_V3.getCode());
            handleDisconnect(session, disconnect);
            return;
        }
        SubscribeManager subMgt = session.getSubscribeManager();
        SubAck subAck = subMgt.subscribe(subscribe, session);
//        SubAck subAck = new SubAck(SUBACK_VALUE << 4);
//        subAck.setPacketIdentifier(subscribe.getPacketIdentifier());
//        int size = subscribe.getTopicFilterList().size();
//        List<Integer> codes = new ArrayList<>();
//        for (int i=0;i<size;i++) {
//            codes.add(0);
//        }
//        subAck.setRespCodes(codes);
        MqttWriter writer   = LOCAL_MQTT_WRITER.get();
        FastBuf    writeBuf = THREAD_WRITE_BUF.get();
        writer.reset();
        encoder.encode(writer, subAck);
        int len = writer.getLength();
        if (writeBuf.writeRemain() > len) {
            writeBuf.write(writer.getData(), writer.getStart(), len);
        }
    }

    default void handleUnsubscribe(MqttNioSession session, Unsubscribe unsubscribe) {
        MqttEncoder encoder = session.getMqttEncoder();
        if (encoder == null) {
            Disconnect disconnect = new Disconnect(DISCONNECT_VALUE << 4);
            disconnect.setReasonCode(ReasonCode.NOT_AUTHORIZED_V3.getCode());
            handleDisconnect(session, disconnect);
            return;
        }
        UnsubAck unsubAck = new UnsubAck(UNSUBACK_VALUE << 4);
        unsubAck.setPacketIdentifier(unsubscribe.getPacketIdentifier());
        int size = unsubscribe.getTopicFilterList().size();
        List<String> topicList = unsubscribe.getTopicFilterList();
        List<String> tppics = new ArrayList<>();
        for (int i=0;i<size;i++) {
            tppics.add(topicList.get(i));
        }
        MqttWriter writer = LOCAL_MQTT_WRITER.get();
        FastBuf writeBuf = THREAD_WRITE_BUF.get();
        writer.reset();
        encoder.encode(writer, unsubAck);
        int len = writer.getLength();
        if (writeBuf.writeRemain() > len) {
            writeBuf.write(writer.getData(), writer.getStart(), len);
        }
    }

    /**
     * 默认处理Ping的请求
     * @param ping ping的请求
     * @param session mqtt协议的连接会话
     * @throws IOException IO异常
     */
    default void handlePing(MqttNioSession session, PingReq ping) throws IOException {
        if (!session.isConnected()) {
            Disconnect disconnect = new Disconnect(0);
            disconnect.setReasonCode(ReasonCode.NOT_AUTHORIZED.getCode());
            handleDisconnect(session, disconnect);
            return;
        }
        FastBuf writeBuf = THREAD_WRITE_BUF.get();
        writeBuf.reset();
        writeBuf.write(PING_RESP_DATA);
        //session.fastWrite(writeBuf);
    }

    default void handleDisconnect(MqttNioSession session, Disconnect disconnect) {
        session.close();
    }

    default void handleAuth(MqttNioSession session, Auth auth) {
    }


}
