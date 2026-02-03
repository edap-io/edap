package io.edap.mqtt.decoder;

import io.edap.buffer.FastBuf;
import io.edap.mqtt.MqttPacketDecoder;
import io.edap.mqtt.ParseContext;
import io.edap.mqtt.packet.Connect;
import io.edap.mqtt.packet.ControlPacket;
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
        long rpos = _buf.rpos();
        int remainBytes = _buf.remain();
        if (remainBytes < 5) {
            r.setFinished(false);
            return r;
        }
        Connect connect = new Connect(fixedHeaderByte);
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
            int len = _buf.get(rpos++) << 8 | _buf.get(rpos++);
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
                _buf.rpos(rpos);
                connect.setConnProperties(parseProperties(_buf, parseContext));
                rpos = _buf.rpos();
            }

            len = _buf.get(rpos++) << 8 | _buf.get(rpos++);
            if (data.length < len) {
                data = new byte[len];
                parseContext.setParseData(data);
            }
            _buf.get(rpos, data, 0, len);
            rpos += len;
            connect.setClientIdentifier(new String(data, 0, len, StandardCharsets.UTF_8));
            if (rpos < _buf.limit() && connect.getWillFlag() > 0 && connect.getProtocolLevel().getValue() > 4) {
                _buf.rpos(rpos);
                connect.setProperties(parseProperties(_buf, parseContext));
                rpos = _buf.rpos();
            }

            if (rpos < _buf.limit()  && connect.getWillFlag() > 0) {
                len = _buf.get(rpos++) << 8 | _buf.get(rpos++);
                if (data.length < len) {
                    data = new byte[len];
                    parseContext.setParseData(data);
                }
                _buf.get(rpos, data, 0, len);
                rpos += len;
                connect.setTopic(new String(data, 0, len, StandardCharsets.UTF_8));
                len = _buf.get(rpos++) << 8 | _buf.get(rpos++);
                if (connect.getProtocolLevel().getValue() < 5) {
                    if (data.length < len) {
                        data = new byte[len];
                        parseContext.setParseData(data);
                    }
                    _buf.get(rpos, data, 0, len);
                    connect.setMessage(new String(data, StandardCharsets.UTF_8));
                } else {
                    byte[] payload = new byte[len];
                    _buf.get(rpos, payload);
                    connect.setPayload(payload);
                }
                rpos += len;
            }

            if (rpos < _buf.limit()  && connect.getUserNameFlag() > 0) {
                len = _buf.get(rpos++) << 8 | _buf.get(rpos++);
                if (data.length < len) {
                    data = new byte[len];
                    parseContext.setParseData(data);
                }
                _buf.get(rpos, data, 0, len);
                rpos += len;
                connect.setUserName(new String(data, 0, len, StandardCharsets.UTF_8));
            }
            if (rpos < _buf.limit() && connect.getPasswordFlag() > 0) {
                len = _buf.get(rpos++) << 8 | _buf.get(rpos++);
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
        } else {
            r.setFinished(false);
        }
        r.setMessage(connect);
        return r;
    }
}
