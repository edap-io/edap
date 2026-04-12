package io.edap.mqtt.decoder;

import io.edap.buffer.FastBuf;
import io.edap.mqtt.*;
import io.edap.mqtt.packet.Publish;
import io.edap.nio.ParseResult;

import java.nio.charset.StandardCharsets;

import static io.edap.mqtt.QoSLevel.EXACTLY_ONCE;
import static io.edap.mqtt.QoSLevel.LEAST_ONCE;

public class PublishDecoder implements MqttPacketDecoder<ControlPacket> {

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
        if (remainBytes >= remain) {
            long oldRpos = rpos;
            int len;
            Publish publish = new Publish(fixedHeaderByte);
            len = _buf.get(rpos++) << 8 | _buf.get(rpos++);
            byte[] data = parseContext.getParseData();
            if (data.length < len) {
                data = new byte[len];
                parseContext.setParseData(data);
            }
            _buf.get(rpos, data, 0, len);
            rpos += len;
            publish.setTopic(new String(data, 0, len, StandardCharsets.UTF_8));
            if (publish.getQos() == LEAST_ONCE || publish.getQos() == EXACTLY_ONCE) {
                publish.setPacketIdentifier((_buf.get(rpos++) & 0xFF) << 8 | _buf.get(rpos++) & 0xFF);
            }
            MqttNioSession session = parseContext.getSession();
            if (session.getProtocolLevel().getValue() > ProtocolLevel.VERSION_3_1_1.getValue()) {
                parseContext.setRpos(rpos);
                publish.setProperties(parseProperties(_buf, parseContext));
                rpos = parseContext.getRpos();
            }
            data = new byte[(int)(remainBytes - (rpos - oldRpos))];
            _buf.get(rpos, data);
            parseContext.setRpos(rpos + data.length);
            _buf.rpos(rpos + data.length);
            publish.setPayload(data);
            r.setMessage(publish);
            r.setFinished(true);
        } else {
            return r;
        }

        return r;
    }
}
