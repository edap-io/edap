package io.edap.mqtt.decoder;

import io.edap.buffer.FastBuf;
import io.edap.log.Logger;
import io.edap.log.LoggerManager;
import io.edap.mqtt.*;
import io.edap.mqtt.ControlPacket;
import io.edap.mqtt.packet.Disconnect;
import io.edap.nio.ParseResult;

import java.util.LinkedHashMap;

public class DisconnectDecoder implements MqttPacketDecoder<ControlPacket> {

    static Logger LOG = LoggerManager.getLogger(DisconnectDecoder.class);

    @Override
    public ParseResult<ControlPacket> parse(FastBuf buf, int fixedHeaderByte, ParseContext parseContext) {
        ParseResult<ControlPacket> r = new ParseResult<>();
        Disconnect disconnect = new Disconnect(fixedHeaderByte);
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
        if (remain == 0) {
            _buf.rpos(parseContext.getRpos());
            r.setFinished(true);
            r.setMessage(disconnect);
            return r;
        }
        MqttNioSession session = parseContext.getSession();
        if (session.getProtocolLevel().getValue() < ProtocolLevel.VERSION_5.getValue()) {
            LOG.error("Mqtt {} must not Variable header", l -> l.arg(session.getProtocolLevel()));
            r.setFinished(true);
            r.setMessage(disconnect);
        } else {
            rpos = parseContext.getRpos();
            disconnect.setReasonCode(buf.get(rpos++) & 0xFF);
            parseContext.setRpos(rpos);
            LinkedHashMap<PropertyType, PacketProperty> props = parseProperties(buf, parseContext);
            disconnect.setProperties(props);
            _buf.rpos(parseContext.getRpos());
            r.setMessage(disconnect);
            r.setFinished(true);
        }

        return r;
    }
}
