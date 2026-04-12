package io.edap.mqtt.decoder;

import io.edap.buffer.FastBuf;
import io.edap.mqtt.MqttPacketDecoder;
import io.edap.mqtt.ParseContext;
import io.edap.mqtt.ControlPacket;
import io.edap.nio.ParseResult;

public class UnsupportDecoder implements MqttPacketDecoder<ControlPacket> {

    @Override
    public ParseResult<ControlPacket> parse(FastBuf buf, int fixedHeaderByte, ParseContext parseContext) {
        throw new RuntimeException("MqttBrokerSession unsupport ControlPacketType [" + fixedHeaderByte + "]");
    }
}
