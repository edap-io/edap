package io.edap.mqtt.broker;

import io.edap.Decoder;
import io.edap.buffer.FastBuf;
import io.edap.mqtt.MqttPacketDecoder;
import io.edap.mqtt.ParseContext;
import io.edap.mqtt.packet.ControlPacket;
import io.edap.nio.ParseResult;

import static io.edap.mqtt.MqttPacketDecoderFactory.*;

public class MqttBaseDecoder implements Decoder<ControlPacket, MqttBrokerSession> {

    static ThreadLocal<io.edap.mqtt.ParseContext> TRL_PARSE_CONTEXT;

    static {
        TRL_PARSE_CONTEXT = ThreadLocal.withInitial(() -> {
            io.edap.mqtt.ParseContext parseContext = new io.edap.mqtt.ParseContext();
            parseContext.setParseData(new byte[4096]);
            parseContext.setResult(new ParseResult<>());
            return parseContext;
        });
    }

    private MqttPacketDecoder[] decoders;

    public MqttBaseDecoder() {
        decoders = new MqttPacketDecoder[16];
        decoders[0]  = UNSUPPORT_DECODER;
        decoders[1]  = CONNECT_DECODER;
        decoders[2]  = UNSUPPORT_DECODER;
        decoders[3]  = PUBLISH_DECODER;
        decoders[4]  = PUB_ACK_DECODER;
        decoders[5]  = PUB_REC_DECODER;
        decoders[6]  = PUB_REL_DECODER;
        decoders[7]  = PUB_COMP_DECODER;
        decoders[8]  = SUBSCRIBE_DECODER;
        decoders[9]  = UNSUPPORT_DECODER;
        decoders[10] = UNSUBSCRIBE_DECODER;
        decoders[11] = UNSUPPORT_DECODER;
        decoders[12] = PING_REQ_DECODER;
        decoders[13] = UNSUPPORT_DECODER;
        decoders[14] = DISCONNECT_DECODER;
        decoders[15] = AUTH_DECODER;
    }

    @Override
    public ParseResult<ControlPacket> decode(FastBuf bufIn, MqttBrokerSession nioSession) {
        FastBuf _buf        = bufIn;
        int     fixedHeader = _buf.get() & 0xFF;
        int     typeValue   = fixedHeader >> 4;
        MqttPacketDecoder decoder = decoders[typeValue];
        ParseContext parseContext = TRL_PARSE_CONTEXT.get();
        parseContext.setSession(nioSession);
        return decoder.parse(bufIn, fixedHeader, parseContext);
    }

    @Override
    public void reset() {

    }
}
