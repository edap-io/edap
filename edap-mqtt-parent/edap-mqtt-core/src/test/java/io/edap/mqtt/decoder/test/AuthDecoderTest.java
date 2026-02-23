package io.edap.mqtt.decoder.test;

import io.edap.buffer.FastBuf;
import io.edap.mqtt.ControlPacket;
import io.edap.mqtt.ControlPacketType;
import io.edap.mqtt.ParseContext;
import io.edap.mqtt.decoder.AuthDecoder;
import io.edap.mqtt.packet.Auth;
import io.edap.nio.ParseResult;
import org.junit.jupiter.api.Test;

import static io.edap.mqtt.test.TestUtil.writeMqttVarInt;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class AuthDecoderTest {

    @Test
    public void testParse() {
        ParseResult<ControlPacket> r;
        AuthDecoder decoder = new AuthDecoder();
        FastBuf buf = new FastBuf(4096);
        int fixHeader = (ControlPacketType.AUTH.getValue() << 4);

        int len = 127;
        buf.write((byte)fixHeader);
        writeMqttVarInt(buf, len);
        ParseContext parseCtx = new ParseContext();

        parseCtx.setRpos(buf.rpos() + 4);
        r = decoder.parse(buf, fixHeader, parseCtx);
        assertEquals(r.isFinished(), false);

        parseCtx.setRpos(buf.rpos() + 1);
        r = decoder.parse(buf, fixHeader, parseCtx);
        assertEquals(r.isFinished(), false);

        len = 128;
        buf.reset();
        buf.write((byte)fixHeader);
        writeMqttVarInt(buf, len);
        parseCtx.setRpos(buf.rpos() + 1);
        r = decoder.parse(buf, fixHeader, parseCtx);
        assertEquals(r.isFinished(), false);

        parseCtx.setRpos(buf.rpos() + 1);
        buf.wpos(buf.wpos() - 1);
        r = decoder.parse(buf, fixHeader, parseCtx);
        assertEquals(r.isFinished(), false);


        len = 16384;
        buf.reset();
        buf.write((byte)fixHeader);
        writeMqttVarInt(buf, len);
        parseCtx.setRpos(buf.rpos() + 1);
        r = decoder.parse(buf, fixHeader, parseCtx);
        assertEquals(r.isFinished(), false);

        parseCtx.setRpos(buf.rpos() + 1);
        buf.wpos(buf.wpos() - 1);
        r = decoder.parse(buf, fixHeader, parseCtx);
        assertEquals(r.isFinished(), false);


        len = 2097152;
        buf.reset();
        buf.write((byte)fixHeader);
        writeMqttVarInt(buf, len);
        parseCtx.setRpos(buf.rpos() + 1);
        r = decoder.parse(buf, fixHeader, parseCtx);
        assertEquals(r.isFinished(), false);

        parseCtx.setRpos(buf.rpos() + 1);
        buf.wpos(buf.wpos() - 1);
        r = decoder.parse(buf, fixHeader, parseCtx);
        assertEquals(r.isFinished(), false);


        len = 2;
        buf.reset();
        buf.write((byte)fixHeader);
        writeMqttVarInt(buf, len);
        buf.write((byte)24);
        buf.write((byte)0);
        parseCtx.setRpos(buf.rpos() + 1);
        r = decoder.parse(buf, fixHeader, parseCtx);
        assertEquals(r.isFinished(), true);
        assertEquals(r.getMessage() instanceof Auth, true);
        Auth auth = (Auth)r.getMessage();
        assertEquals(auth.getReasonCode(), 24);
        assertEquals(buf.rpos(), buf.wpos());
    }
}
