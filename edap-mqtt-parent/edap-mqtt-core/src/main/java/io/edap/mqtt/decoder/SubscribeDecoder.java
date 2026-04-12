package io.edap.mqtt.decoder;

import io.edap.buffer.FastBuf;
import io.edap.mqtt.*;
import io.edap.mqtt.ControlPacket;
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
        Subscribe subscribe = new Subscribe(fixedHeaderByte);
        int packetIdentifer = (_buf.get(rpos++) & 0xFF) << 8 | _buf.get(rpos++) & 0xFF;
        subscribe.setPacketIdentifier(packetIdentifer);
        MqttNioSession session = parseContext.getSession();
        if (session.getProtocolLevel().getValue() > ProtocolLevel.VERSION_3_1_1.getValue()) {
            parseContext.setRpos(rpos);
            subscribe.setProperties(parseProperties(_buf, parseContext));
            rpos = parseContext.getRpos();
        }
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
        r.setMessage(subscribe);

        return r;
    }
}
