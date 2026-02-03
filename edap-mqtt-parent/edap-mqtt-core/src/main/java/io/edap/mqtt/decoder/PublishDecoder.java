package io.edap.mqtt.decoder;

import io.edap.buffer.FastBuf;
import io.edap.mqtt.MqttNioSession;
import io.edap.mqtt.MqttPacketDecoder;
import io.edap.mqtt.ParseContext;
import io.edap.mqtt.ProtocolLevel;
import io.edap.mqtt.packet.ControlPacket;
import io.edap.mqtt.packet.Publish;
import io.edap.nio.ParseResult;

import java.nio.charset.StandardCharsets;

public class PublishDecoder implements MqttPacketDecoder<ControlPacket> {

    @Override
    public ParseResult<ControlPacket> parse(FastBuf buf, int fixedHeaderByte, ParseContext parseContext) {
        ParseResult<ControlPacket> r = parseContext.getResult();
        r.setFinished(false);
        r.setMessage(null);
        FastBuf _buf = buf;
        long rpos = _buf.rpos();
        int remainBytes = _buf.remain();
        if (remainBytes < 5) {
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
            if (publish.getQos().getValue() == 1 || publish.getQos().getValue() == 2) {
                publish.setPacketIdentifier((_buf.get(rpos++) & 0xFF) << 8 | _buf.get(rpos++) & 0xFF);
            }
            MqttNioSession session = parseContext.getSession();
            if (session.getProtocolLevel().getValue() > ProtocolLevel.VERSION_3_1_1.getValue()) {
                _buf.rpos(rpos);
                publish.setProperties(parseProperties(_buf, parseContext));
                rpos = _buf.rpos();
            }
            data = new byte[(int)(remainBytes - (rpos - oldRpos))];
            _buf.get(rpos, data);
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
