package io.edap.mqtt.wire;

public class PingReq extends ControlPacket {

    public PingReq(int fixedHeaderByte) {
        super(ControlPacketType.PINGREQ, fixedHeaderByte);
    }
}
