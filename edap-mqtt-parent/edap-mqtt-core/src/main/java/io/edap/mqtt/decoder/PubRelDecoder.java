package io.edap.mqtt.decoder;

import io.edap.buffer.FastBuf;
import io.edap.mqtt.MqttNioSession;
import io.edap.mqtt.MqttPacketDecoder;
import io.edap.mqtt.ParseContext;
import io.edap.mqtt.ProtocolLevel;
import io.edap.mqtt.packet.Connect;
import io.edap.mqtt.packet.ControlPacket;
import io.edap.mqtt.packet.PubRel;
import io.edap.nio.ParseResult;

public class PubRelDecoder implements MqttPacketDecoder<ControlPacket> {
    @Override
    public ParseResult<ControlPacket> parse(FastBuf buf, int fixedHeaderByte, ParseContext parseContext) {
        ParseResult<ControlPacket> r = parseContext.getResult();
        FastBuf _buf = buf;
        long rpos = _buf.rpos();
        int remainBytes = _buf.remain();
        if (remainBytes < 3) {
            r.setFinished(false);
            return r;
        }
        PubRel pubRel = new PubRel(fixedHeaderByte);
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
            pubRel.setPacketIdentifier((_buf.get(rpos++) & 0xFF) << 8 | _buf.get(rpos++) & 0xFF);
            MqttNioSession session = parseContext.getSession();
            if (session.getProtocolLevel().getValue() > ProtocolLevel.VERSION_3_1_1.getValue()) {
                pubRel.setReasonCode(_buf.get(rpos++) & 0xFF);
                _buf.rpos(rpos);
                pubRel.setProperties(parseProperties(_buf, parseContext));
                rpos = _buf.rpos();
            }
            r.setFinished(true);
            r.setMessage(pubRel);
            _buf.rpos(rpos);
        } else {
            r.setFinished(false);
        }

        return r;
    }
}
