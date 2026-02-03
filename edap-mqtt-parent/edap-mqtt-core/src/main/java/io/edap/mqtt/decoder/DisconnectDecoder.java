package io.edap.mqtt.decoder;

import io.edap.buffer.FastBuf;
import io.edap.log.Logger;
import io.edap.log.LoggerManager;
import io.edap.mqtt.*;
import io.edap.mqtt.packet.ControlPacket;
import io.edap.mqtt.packet.Disconnect;
import io.edap.nio.ParseResult;

import java.util.LinkedHashMap;

public class DisconnectDecoder implements MqttPacketDecoder<ControlPacket> {

    static Logger LOG = LoggerManager.getLogger(DisconnectDecoder.class);

    @Override
    public ParseResult<ControlPacket> parse(FastBuf buf, int fixedHeaderByte, ParseContext parseContext) {
        ParseResult<ControlPacket> r = new ParseResult<>();
        Disconnect disconnect = new Disconnect(fixedHeaderByte);
        int varFirst = buf.get() & 0xFF;
        // 如果 版本小于5，没有varheader直接返回
        if (varFirst == 0) {
            r.setFinished(true);
            r.setMessage(disconnect);
            return r;
        }

        int remain;
        FastBuf _buf = buf;
        long rpos = _buf.rpos();
        if (varFirst > 0) {
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
        MqttNioSession session = parseContext.getSession();
        if (session.getProtocolLevel().getValue() < ProtocolLevel.VERSION_5.getValue()) {
            LOG.error("Mqtt {} must not Variable header", l -> l.arg(session.getProtocolLevel()));
        } else {
            disconnect.setReasonCode(buf.get() & 0xFF);
            LinkedHashMap<PropertyType, PacketProperty> props = parseProperties(buf, parseContext);
            disconnect.setProperties(props);
            r.setMessage(disconnect);
            r.setFinished(true);
        }

        return r;
    }
}
