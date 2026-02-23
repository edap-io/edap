package io.edap.mqtt.decoder;

import io.edap.buffer.FastBuf;
import io.edap.mqtt.MqttPacketDecoder;
import io.edap.mqtt.ParseContext;
import io.edap.mqtt.ControlPacket;
import io.edap.mqtt.ControlPacketType;
import io.edap.mqtt.packet.PingResp;
import io.edap.nio.ParseResult;

public class PingRespDecoder implements MqttPacketDecoder<ControlPacket> {

    static PingResp PING_RESP = new PingResp(ControlPacketType.PINGRESP.getValue());

    @Override
    public ParseResult<ControlPacket> parse(FastBuf buf, int fixedHeaderByte, ParseContext parseContext) {
        ParseResult<ControlPacket> r = parseContext.getResult();
        long rpos = parseContext.getRpos();
        if (rpos >= buf.wpos()) {
            r.setFinished(false);
            return r;
        }
        int remain = buf.get(rpos++) & 0xFF;
        r.setFinished(true);
        buf.rpos(rpos);
        r.setMessage(PING_RESP);
        parseContext.setRpos(rpos);

        return r;
    }
}
