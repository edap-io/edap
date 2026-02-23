package io.edap.mqtt.decoder;

import io.edap.buffer.FastBuf;
import io.edap.mqtt.*;
import io.edap.mqtt.packet.Unsubscribe;
import io.edap.nio.ParseResult;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class UnsubscribeDecoder implements MqttPacketDecoder<ControlPacket> {
    @Override
    public ParseResult<ControlPacket> parse(FastBuf buf, int fixedHeaderByte, ParseContext parseContext) {
        ParseResult<ControlPacket> r = parseContext.getResult();
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
        int packetIdentifer = (_buf.get(rpos++) & 0xFF) << 8 | _buf.get(rpos++) & 0xFF;
        Unsubscribe unsubscribe = new Unsubscribe(fixedHeaderByte);
        unsubscribe.setPacketIdentifier(packetIdentifer);
        int topicLen;
        List<String> topicFilters = new ArrayList<>();
        MqttNioSession session = parseContext.getSession();
        if (session.getProtocolLevel().getValue() > ProtocolLevel.VERSION_3_1_1.getValue()) {
            parseContext.setRpos(rpos);
            unsubscribe.setProperties(parseProperties(buf, parseContext));
            rpos = parseContext.getRpos();
        }
        while (rpos < limit) {
            topicLen = (_buf.get(rpos++) & 0xFF) << 8 | _buf.get(rpos++) & 0xFF;
            byte[] data = parseContext.getParseData();
            if (data.length < topicLen) {
                data = new byte[topicLen];
                parseContext.setParseData(data);
            }
            _buf.get(rpos, data, 0, topicLen);
            rpos += topicLen;
            topicFilters.add(new String(data, 0, topicLen, StandardCharsets.UTF_8));
        }
        parseContext.setRpos(rpos);
        _buf.rpos(rpos);
        r.setFinished(true);
        unsubscribe.setTopicFilterList(topicFilters);
        r.setMessage(unsubscribe);
        return r;
    }
}
