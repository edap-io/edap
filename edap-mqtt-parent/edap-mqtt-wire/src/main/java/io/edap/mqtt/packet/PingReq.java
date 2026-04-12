package io.edap.mqtt.packet;

import io.edap.mqtt.ControlPacket;
import io.edap.mqtt.ControlPacketType;

public class PingReq extends ControlPacket {

    public PingReq(int fixedHeaderByte) {
        super(ControlPacketType.PINGREQ, fixedHeaderByte);
    }
}
