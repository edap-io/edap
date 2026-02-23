package io.edap.mqtt.decoder.test;

import io.edap.buffer.FastBuf;
import io.edap.mqtt.ControlPacket;
import io.edap.mqtt.ControlPacketType;
import io.edap.mqtt.ParseContext;
import io.edap.mqtt.decoder.PingReqDecoder;
import io.edap.mqtt.test.MqttNioSessionV311;
import io.edap.nio.ParseResult;
import org.junit.jupiter.api.Test;

import static io.edap.mqtt.test.TestUtil.writeMqttVarInt;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class PingReqDecoderTest {

    @Test
    public void testParse() {
        PingReqDecoder decoder = new PingReqDecoder();
        ParseResult<ControlPacket> r;
        FastBuf buf = new FastBuf(4096);
        int fixHeader = (ControlPacketType.PINGREQ.getValue() << 4);

        int len = 127;
        buf.write((byte) fixHeader);
        writeMqttVarInt(buf, len);
        ParseContext parseCtx = new ParseContext();
        parseCtx.setSession(new MqttNioSessionV311());
        parseCtx.setRpos(buf.rpos());
        parseCtx.setParseData(new byte[4]);
        parseCtx.setRpos(buf.rpos() + 2);
        parseCtx.setResult(new ParseResult<>());
        r = decoder.parse(buf, fixHeader, parseCtx);
        assertEquals(r.isFinished(), false);

        len = 0;
        buf.reset();
        buf.write((byte) fixHeader);
        writeMqttVarInt(buf, len);
        parseCtx = new ParseContext();
        parseCtx.setSession(new MqttNioSessionV311());
        parseCtx.setRpos(buf.rpos());
        parseCtx.setParseData(new byte[4]);
        parseCtx.setRpos(buf.rpos() + 1);
        parseCtx.setResult(new ParseResult<>());
        r = decoder.parse(buf, fixHeader, parseCtx);
        assertEquals(r.isFinished(), true);
        assertEquals(buf.wpos(), parseCtx.getRpos());
        assertEquals(buf.wpos(), buf.rpos());
    }
}
