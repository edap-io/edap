package io.edap.mqtt.decoder;

import io.edap.buffer.FastBuf;
import io.edap.mqtt.MqttPacketDecoder;
import io.edap.mqtt.ParseContext;
import io.edap.mqtt.packet.ControlPacket;
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
        r.setMessage(null);
        FastBuf _buf = buf;
        long rpos = _buf.rpos();
        int remainBytes = _buf.remain();
        if (remainBytes < 5) {
            r.setFinished(false);
            return r;
        }
        int remain;
        int varFirst = _buf.get(rpos++);
        if (varFirst >= 0) {
            remain = varFirst;
        } else {
            int varTwo = _buf.get(rpos++);
            if (varTwo > 0) {
                remain = (varTwo & 0x7F) << 7 | (varFirst & 0x7F);
            } else {
                int varThree = _buf.get(rpos++);
                if (varThree > 0) {
                    remain = (varThree & 0x7F) << 14 | (varTwo & 0x7F) << 7 | (varFirst & 0x7F);
                } else {
                    remain = (_buf.get(rpos++) & 0x7F) << 21 | (varThree & 0x7F) << 14 | (varTwo & 0x7F) << 7 | (varFirst & 0x7F);
                }
            }
        }
        remainBytes = (int)(_buf.limit() - rpos);
        if (remainBytes >= remain) {
            int packetIdentifer = (_buf.get(rpos++) & 0xFF) << 8 | _buf.get(rpos++) & 0xFF;
            Unsubscribe unsubscribe = new Unsubscribe(fixedHeaderByte);
            unsubscribe.setPacketIdentifier(packetIdentifer);
            long limit = _buf.limit();
            int topicLen;
            List<String> topicFilters = new ArrayList<>();
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
            _buf.rpos(rpos);
            r.setFinished(true);
            unsubscribe.setTopicFilterList(topicFilters);
            r.setMessage(unsubscribe);
        } else {
            r.setFinished(false);
        }
        return r;
    }
}
