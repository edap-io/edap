package io.edap.mqtt;

import io.edap.mqtt.packet.*;

import java.util.List;

import static io.edap.mqtt.ControlPacketType.*;

public interface MqttEncoder {

    ThreadLocal<MqttWriter> LOCAL_MQTT_WRITER =
            ThreadLocal.withInitial(() -> new MqttWriter());

    default void encode(MqttWriter writer, PubAck pubAck) {
        int pi = pubAck.getPacketIdentifier();
        writer.writeBytes(
                (byte)(PUBACK.getValue() << 4 | pubAck.getLowFourBits()),
                (byte)2,
                (byte)(pi >> 8),
                (byte)(pi & 0xFF));
    }

    default void encode(MqttWriter writer, ConnAck connAck) {
        writer.writeBytes(
                (byte)(CONNACK.getValue() << 4 | connAck.getLowFourBits()),
                (byte)2,
                (byte)connAck.getConnAckFlag(),
                (byte)connAck.getConnAckCode());
    }

    default void encode(MqttWriter writer, PubComp pubComp) {
        int pi = pubComp.getPacketIdentifier();
        writer.writeBytes(
                (byte)(PUBACK.getValue() << 4 | pubComp.getLowFourBits()),
                (byte)2,
                (byte)(pi >> 8),
                (byte)(pi & 0xFF));
    }

    default void encode(MqttWriter writer, PubRec pubRec) {
        int pi = pubRec.getPacketIdentifier();
        writer.writeBytes(
                (byte)(PUBREC.getValue() << 4 | pubRec.getLowFourBits()),
                (byte)2,
                (byte)(pi >> 8),
                (byte)(pi & 0xFF));
    }

    default void encode(MqttWriter writer, SubAck subAck) {
        int pi = subAck.getPacketIdentifier();
        List<Integer> codes = subAck.getRespCodes();
        int size = codes.size();
        writer.writeByte((byte)(SUBACK.getValue() << 4 | subAck.getLowFourBits()));
        writer.writeVarInt(size + 2);
        writer.writeBytes((byte)(pi >> 8), (byte)(pi & 0xFF));
        int pos = writer.getPos();
        writer.expand(size);
        for (int i=0;i<size;i++) {
            int v = codes.get(i).intValue();
            if (v <= 2) {
                writer.writeByte(pos++, (byte)v);
            } else {
                writer.writeByte(pos++, (byte)0x80);
            }
        }
        writer.setPos(pos);
    }
}
