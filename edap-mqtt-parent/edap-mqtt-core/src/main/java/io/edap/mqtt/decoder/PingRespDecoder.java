package io.edap.mqtt.decoder;

import io.edap.buffer.FastBuf;
import io.edap.mqtt.MqttPacketDecoder;
import io.edap.mqtt.ParseContext;
import io.edap.mqtt.packet.ControlPacket;
import io.edap.mqtt.ControlPacketType;
import io.edap.mqtt.packet.PingResp;
import io.edap.nio.ParseResult;

public class PingRespDecoder implements MqttPacketDecoder<ControlPacket> {

    static PingResp PING_RESP = new PingResp(ControlPacketType.PINGRESP.getValue());

    @Override
    public ParseResult<ControlPacket> parse(FastBuf buf, int fixedHeaderByte, ParseContext parseContext) {
        ParseResult<ControlPacket> r = parseContext.getResult();
        if (buf.remain() <= 0) {
            r.setFinished(false);
            return r;
        }
        int remain = buf.get() & 0xFF;
        r.setFinished(true);
        r.setMessage(PING_RESP);

        return r;
    }
}
