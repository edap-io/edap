package io.edap.mqtt.decoder;

import io.edap.buffer.FastBuf;
import io.edap.mqtt.*;
import io.edap.mqtt.packet.PubAck;
import io.edap.nio.ParseResult;

public class PubAckDecoder implements MqttPacketDecoder<ControlPacket> {

    @Override
    public ParseResult<ControlPacket> parse(FastBuf buf, int fixedHeaderByte, ParseContext parseContext) {
        ParseResult<ControlPacket> r = new ParseResult<>();
        PubAck pubAck = new PubAck(fixedHeaderByte);
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
        long originalPos = rpos;
        int remainBytes = (int)(_buf.limit() - rpos);
        if (remainBytes < remain) {
            return r;
        }

        int packetIdentifier = _buf.get(rpos++) << 8 | _buf.get(rpos++) & 0xFF;
        pubAck.setPacketIdentifier(packetIdentifier);

        if (rpos < _buf.limit()) {
            MqttNioSession session = parseContext.getSession();
            if (session.getProtocolLevel().getValue() < ProtocolLevel.VERSION_5.getValue()) {
                parseContext.setRpos(originalPos + remain);
                r.setFinished(true);
                r.setMessage(pubAck);
                _buf.rpos(rpos);
                return r;
            }
            pubAck.setReasonCode(_buf.get(rpos++) & 0xFF);
            parseContext.setRpos(rpos);
            pubAck.setProperties(parseProperties(buf, parseContext));
            rpos = parseContext.getRpos();
        }
        parseContext.setRpos(rpos);
        _buf.rpos(rpos);
        r.setMessage(pubAck);
        r.setFinished(true);
        return r;
    }
}
