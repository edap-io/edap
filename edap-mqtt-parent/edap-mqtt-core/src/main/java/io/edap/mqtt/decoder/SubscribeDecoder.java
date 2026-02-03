package io.edap.mqtt.decoder;

import io.edap.buffer.FastBuf;
import io.edap.mqtt.*;
import io.edap.mqtt.packet.ControlPacket;
import io.edap.mqtt.packet.Subscribe;
import io.edap.mqtt.packet.TopicFilter;
import io.edap.nio.ParseResult;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class SubscribeDecoder implements MqttPacketDecoder<ControlPacket> {
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
        Subscribe subscribe = new Subscribe(fixedHeaderByte);
        remainBytes = (int)(_buf.limit() - rpos);
        if (remainBytes >= remain) {
            int packetIdentifer = (_buf.get(rpos++) & 0xFF) << 8 | _buf.get(rpos++) & 0xFF;
            subscribe.setPacketIdentifier(packetIdentifer);
            MqttNioSession session = parseContext.getSession();
            if (session.getProtocolLevel().getValue() > ProtocolLevel.VERSION_3_1_1.getValue()) {
                _buf.rpos(rpos);
                subscribe.setProperties(parseProperties(_buf, parseContext));
                rpos = _buf.rpos();
            }
            long limit = _buf.limit();
            int topicLen;
            List<TopicFilter> topicFilters = new ArrayList<>();
            while (rpos < limit) {
                topicLen = (_buf.get(rpos++) & 0xFF) << 8 | _buf.get(rpos++) & 0xFF;
                byte[] data = parseContext.getParseData();
                if (data.length < topicLen) {
                    data = new byte[topicLen];
                    parseContext.setParseData(data);
                }
                _buf.get(rpos, data, 0, topicLen);
                rpos += topicLen;
                TopicFilter tf = new TopicFilter();
                tf.setTopicFilter(new String(data, 0, topicLen, StandardCharsets.UTF_8));
                tf.setSubscriptionOptions(_buf.get(rpos++));
                topicFilters.add(tf);
            }
            _buf.rpos(rpos);
            r.setFinished(true);
            subscribe.setTopicFilterList(topicFilters);
        } else {
            r.setFinished(false);
        }
        r.setMessage(subscribe);

        return r;
    }
}
