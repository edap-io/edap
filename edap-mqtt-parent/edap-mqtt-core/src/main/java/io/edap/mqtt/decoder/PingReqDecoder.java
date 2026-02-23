package io.edap.mqtt.decoder;

import io.edap.buffer.FastBuf;
import io.edap.mqtt.MqttPacketDecoder;
import io.edap.mqtt.ParseContext;
import io.edap.mqtt.ControlPacket;
import io.edap.mqtt.ControlPacketType;
import io.edap.mqtt.packet.PingReq;
import io.edap.nio.ParseResult;

public class PingReqDecoder implements MqttPacketDecoder<ControlPacket> {

    static PingReq PING_REQ = new PingReq(ControlPacketType.PINGREQ.getValue());

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
        r.setMessage(PING_REQ);
        parseContext.setRpos(rpos);
        buf.rpos(rpos);
        return r;
    }
}
