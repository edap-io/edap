package io.edap.mqtt.decoder;

import io.edap.buffer.FastBuf;
import io.edap.mqtt.*;
import io.edap.mqtt.packet.UnsubAck;
import io.edap.nio.ParseResult;

import java.util.ArrayList;
import java.util.List;

public class UnsubAckDecoder implements MqttPacketDecoder<ControlPacket> {
    @Override
    public ParseResult<ControlPacket> parse(FastBuf buf, int fixedHeaderByte, ParseContext parseContext) {
        ParseResult<ControlPacket> r = new ParseResult<>();
        UnsubAck unsubAck = new UnsubAck(fixedHeaderByte);
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
        unsubAck.setPacketIdentifier(packetIdentifier);

        if (rpos < limit) {
            MqttNioSession session = parseContext.getSession();
            if (session.getProtocolLevel().getValue() < ProtocolLevel.VERSION_5.getValue()) {
                parseContext.setRpos(originalPos + remain);
                r.setFinished(true);
                r.setMessage(unsubAck);
                return r;
            }
            parseContext.setRpos(rpos);
            unsubAck.setProperties(parseProperties(buf, parseContext));
            rpos = parseContext.getRpos();
            List<Integer> reasonCodes = new ArrayList<>();
            while (rpos < limit) {
                reasonCodes.add(_buf.get(rpos++) & 0xFF);
            }
            unsubAck.setReasonCodes(reasonCodes);
        }
        parseContext.setRpos(rpos);
        _buf.rpos(rpos);
        r.setMessage(unsubAck);
        r.setFinished(true);
        return r;
    }
}
