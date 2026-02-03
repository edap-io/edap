package io.edap.mqtt.packet;

import io.edap.mqtt.ControlPacketType;

public class PingResp extends ControlPacket {

    public PingResp(int fixedHeaderByte) {
        super(ControlPacketType.PINGRESP, fixedHeaderByte);
    }
}