package io.edap.mqtt.decoder;

import io.edap.buffer.FastBuf;
import io.edap.mqtt.*;
import io.edap.mqtt.packet.SubAck;
import io.edap.nio.ParseResult;

import java.util.ArrayList;
import java.util.List;

public class SubAckDecoder implements MqttPacketDecoder<ControlPacket> {
    @Override
    public ParseResult<ControlPacket> parse(FastBuf buf, int fixedHeaderByte, ParseContext parseContext) {
        ParseResult<ControlPacket> r = new ParseResult<>();
        SubAck subAck = new SubAck(fixedHeaderByte);
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

        int packetIdentifier = _buf.get(rpos++) << 8 | _buf.get(rpos++) & 0xFF;
        subAck.setPacketIdentifier(packetIdentifier);

        MqttNioSession session = parseContext.getSession();
        if (session.getProtocolLevel().getValue() > ProtocolLevel.VERSION_3_1_1.getValue()) {
            parseContext.setRpos(rpos);
            subAck.setProperties(parseProperties(buf, parseContext));
            rpos = parseContext.getRpos();
        }

        List<Integer> respCodes = new ArrayList<>();
        while (rpos < _buf.limit()) {
            respCodes.add(_buf.get(rpos++) & 0xFF);
        }
        subAck.setRespCodes(respCodes);
        parseContext.setRpos(rpos);
        _buf.rpos(rpos);
        r.setMessage(subAck);
        r.setFinished(true);
        return r;
    }
}
