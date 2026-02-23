package io.edap.mqtt.decoder;

import io.edap.buffer.FastBuf;
import io.edap.mqtt.MqttPacketDecoder;
import io.edap.mqtt.ParseContext;
import io.edap.mqtt.packet.Connect;
import io.edap.mqtt.ControlPacket;
import io.edap.mqtt.ProtocolLevel;
import io.edap.nio.ParseResult;

import java.nio.charset.StandardCharsets;

public class ConnectDecoder implements MqttPacketDecoder<ControlPacket> {

    @Override
    public ParseResult<ControlPacket> parse(FastBuf buf, int fixedHeaderByte, ParseContext parseContext) {
        ParseResult<ControlPacket> r = parseContext.getResult();
        r.setFinished(false);
        r.setMessage(null);
        FastBuf _buf = buf;
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

        Connect connect = new Connect(fixedHeaderByte);
        int len = _buf.get(rpos++) << 8 | _buf.get(rpos++) & 0xFF;
        byte[] data = parseContext.getParseData();
        if (data.length < len) {
            data = new byte[len];
            parseContext.setParseData(data);
        }
        _buf.get(rpos, data, 0, len);
        rpos += len;
        connect.setProtocolName(new String(data, 0, len, StandardCharsets.UTF_8));
        connect.setProtocolLevel(ProtocolLevel.fromValue(_buf.get(rpos++)));
        int flag = _buf.get(rpos++) & 0xFF;
        connect.setUserNameFlag(flag >> 7);
        connect.setPasswordFlag((flag & 0x7F) >> 6);
        connect.setWillRetain((flag & 0x3F) >> 5);
        connect.setWillQoS((flag & 0x1F) >> 3);
        connect.setWillFlag((flag & 0x7) >> 2);
        connect.setCleanSessionFlag((flag & 0x3) >> 1);
        connect.setReserved((flag & 0x1));

        int keepAlive = (_buf.get(rpos++) & 0xFF) << 8 | _buf.get(rpos++) & 0xFF;
        connect.setKeepAlive(keepAlive);

        if (connect.getProtocolLevel().getValue() > 4) {
            parseContext.setRpos(rpos);
            connect.setConnProperties(parseProperties(_buf, parseContext));
            rpos = parseContext.getRpos();
        }

        len = _buf.get(rpos++) << 8 | _buf.get(rpos++) & 0xFF;
        if (data.length < len) {
            data = new byte[len];
            parseContext.setParseData(data);
        }
        _buf.get(rpos, data, 0, len);
        rpos += len;
        connect.setClientIdentifier(new String(data, 0, len, StandardCharsets.UTF_8));
        if (rpos < _buf.limit() && connect.getWillFlag() > 0 && connect.getProtocolLevel().getValue() > 4) {
            parseContext.setRpos(rpos);
            connect.setProperties(parseProperties(_buf, parseContext));
            rpos = parseContext.getRpos();
        }

        if (rpos < _buf.limit()  && connect.getWillFlag() > 0) {
            len = _buf.get(rpos++) << 8 | _buf.get(rpos++) & 0xFF;
            if (data.length < len) {
                data = new byte[len];
                parseContext.setParseData(data);
            }
            _buf.get(rpos, data, 0, len);
            rpos += len;
            connect.setWillTopic(new String(data, 0, len, StandardCharsets.UTF_8));
            len = _buf.get(rpos++) << 8 | _buf.get(rpos++) & 0xFF;
            byte[] payload = new byte[len];
            _buf.get(rpos, payload);
            connect.setWillPayload(payload);
            rpos += len;
        }

        if (rpos < _buf.limit()  && connect.getUserNameFlag() > 0) {
            len = _buf.get(rpos++) << 8 | _buf.get(rpos++) & 0xFF;
            if (data.length < len) {
                data = new byte[len];
                parseContext.setParseData(data);
            }
            _buf.get(rpos, data, 0, len);
            rpos += len;
            connect.setUserName(new String(data, 0, len, StandardCharsets.UTF_8));
        }
        if (rpos < _buf.limit() && connect.getPasswordFlag() > 0) {
            len = _buf.get(rpos++) << 8 | _buf.get(rpos++) & 0xFF;
            if (data.length < len) {
                data = new byte[len];
                parseContext.setParseData(data);
            }
            _buf.get(rpos, data, 0, len);
            rpos += len;
            connect.setPassword(new String(data, 0, len, StandardCharsets.UTF_8));
        }
        _buf.rpos(rpos);
        r.setFinished(true);

        r.setMessage(connect);
        return r;
    }
}
