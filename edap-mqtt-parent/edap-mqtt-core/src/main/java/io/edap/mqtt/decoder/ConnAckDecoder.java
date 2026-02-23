package io.edap.mqtt.decoder;

import io.edap.buffer.FastBuf;
import io.edap.mqtt.*;
import io.edap.mqtt.packet.ConnAck;
import io.edap.nio.ParseResult;

public class ConnAckDecoder implements MqttPacketDecoder<ControlPacket> {
    @Override
    public ParseResult<ControlPacket> parse(FastBuf buf, int fixedHeaderByte, ParseContext parseContext) {
        ParseResult<ControlPacket> r = new ParseResult<>();
        r.setFinished(false);
        FastBuf _buf  = buf;
        long    rpos  = parseContext.getRpos();
        long    limit = _buf.limit();
        if (rpos >= limit) {
            return r;
        }
        int remain = MqttPacketDecoder.parseRemain(buf, parseContext);
        if (remain < 0) {
            return r;
        }
        rpos = parseContext.getRpos();
        int remainBytes = (int)(_buf.limit() - rpos);
        if (remainBytes < remain) {
            return r;
        }

        ConnAck connAck = new ConnAck(fixedHeaderByte);
        connAck.setConnAckFlag(_buf.get(rpos++) & 0xFF);
        connAck.setConnAckCode(_buf.get(rpos++) & 0xFF);
        MqttNioSession session = parseContext.getSession();
        if (session.getProtocolLevel().getValue() > ProtocolLevel.VERSION_3_1_1.getValue()) {
            parseContext.setRpos(rpos);
            connAck.setProperties(parseProperties(_buf, parseContext));
            rpos = parseContext.getRpos();
        }
        r.setMessage(connAck);
        r.setFinished(true);
        _buf.rpos(rpos);
        return r;
    }
}
