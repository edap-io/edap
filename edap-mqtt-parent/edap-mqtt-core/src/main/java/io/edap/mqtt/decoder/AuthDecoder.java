package io.edap.mqtt.decoder;

import io.edap.buffer.FastBuf;
import io.edap.mqtt.MqttPacketDecoder;
import io.edap.mqtt.ParseContext;
import io.edap.mqtt.packet.Auth;
import io.edap.mqtt.ControlPacket;
import io.edap.nio.ParseResult;

public class AuthDecoder implements MqttPacketDecoder<ControlPacket> {

    @Override
    public ParseResult<ControlPacket> parse(FastBuf buf, int fixedHeaderByte, ParseContext parseContext) {
        ParseResult<ControlPacket> r = new ParseResult<>();
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
        int remainBytes = (int)(_buf.limit() - rpos);
        if (remainBytes < remain) {
            r.setFinished(false);
            return r;
        }
        Auth auth = new Auth(fixedHeaderByte);
        rpos = parseContext.getRpos();
        auth.setReasonCode(_buf.get(rpos++) & 0xFF);
        parseContext.setRpos(rpos);
        auth.setProperties(parseProperties(_buf, parseContext));
        rpos = parseContext.getRpos();
        buf.rpos(rpos);
        r.setFinished(true);
        r.setMessage(auth);
        return r;
    }
}
