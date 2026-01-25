package io.edap.mqtt.wire;

public class PingResp extends ControlPacket {

    public PingResp(int fixedHeaderByte) {
        super(ControlPacketType.PINGRESP, fixedHeaderByte);
    }
}
