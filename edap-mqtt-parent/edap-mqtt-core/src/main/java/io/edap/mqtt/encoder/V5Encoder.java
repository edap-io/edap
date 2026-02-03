package io.edap.mqtt.encoder;

import io.edap.mqtt.*;
import io.edap.mqtt.packet.ConnAck;
import io.edap.mqtt.packet.SubAck;
import io.edap.mqtt.property.StringPair;
import io.edap.mqtt.property.UserProperty;
import io.edap.util.CollectionUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static io.edap.mqtt.ControlPacketType.CONNACK;
import static io.edap.mqtt.ControlPacketType.SUBACK;

public class V5Encoder implements MqttEncoder {

    public void encode(MqttWriter writer, ConnAck connAck) {
        // ConnAck的属性为空
        if (CollectionUtils.isEmpty(connAck.getProperties())) {
            writer.writeBytes(
                    (byte)(CONNACK.getValue() << 4 | connAck.getLowFourBits()),
                    (byte)3,
                    (byte)connAck.getConnAckFlag(),
                    (byte)connAck.getConnAckCode(),
                    (byte)0);
            return;
        }

        // 预留Packet的第一个字节，长度(多多4个字节)，properties动态长度(最多4个字节)，Connect Acknowledge Flags(1个字节)，
        // reasonCode(1个字节)
        writer.setStart(11);

        LinkedHashMap<PropertyType, PacketProperty> properties = connAck.getProperties();
        for (Map.Entry<PropertyType, PacketProperty> entry : properties.entrySet()) {
            writer.writeByte((byte)entry.getKey().getType());
            entry.getValue().writeTo(writer);
        }
        int varHeaderLen = writer.getPos() - writer.getStart();
        writer.setStart(writer.getStart() - 1);
        writer.writeLength(varHeaderLen);
        int start = writer.getStart();
        writer.writeByte(start--, (byte)connAck.getConnAckCode());
        writer.writeByte(start, (byte)connAck.getConnAckFlag());
        writer.setStart(start - 1);
        varHeaderLen = writer.getPos() - start;
        writer.writeLength(varHeaderLen);
        start = writer.getStart();
        writer.writeByte(start, (byte)(CONNACK.getValue() << 4 | connAck.getLowFourBits()));
        writer.setStart(start);
    }

    public void encode(MqttWriter writer, SubAck subAck) {
        int pi = subAck.getPacketIdentifier();
        List<Integer> codes = subAck.getRespCodes();
        writer.setStart(11);
        if (CollectionUtils.isEmpty(subAck.getProperties())) {
            writer.writeBytes((byte)0, (byte)(pi >> 8), (byte)(pi & 0xFF));
        } else {
            for (Map.Entry<PropertyType, PacketProperty> entry : subAck.getProperties().entrySet()) {
                writer.writeByte((byte)entry.getKey().getType());
                entry.getValue().writeTo(writer);
            }
            writer.writeBytes((byte)(pi >> 8), (byte)(pi & 0xFF));
            int varHeaderLen = writer.getPos() - writer.getStart();
            writer.setStart(writer.getStart() - 1);
            writer.writeLength(varHeaderLen);
        }
        int size = codes.size();
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
        int varHeaderLen = writer.getPos() - writer.getStart();
        writer.writeLength(varHeaderLen);
        int start = writer.getStart();
        writer.writeByte(start, (byte)(SUBACK.getValue() << 4 | subAck.getLowFourBits()));
        writer.setStart(start);
    }
}
