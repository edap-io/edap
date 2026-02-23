package io.edap.mqtt.decoder.test;

import io.edap.buffer.FastBuf;
import io.edap.mqtt.IntegerToLongException;
import io.edap.mqtt.ParseContext;
import io.edap.mqtt.decoder.UnsupportDecoder;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class UnsupportDecoderTest {

    @Test
    public void testParse() {
        UnsupportDecoder decoder = new UnsupportDecoder();
        int fixedHeaderByte = new Random().nextInt(16);
        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> {
                    decoder.parse(new FastBuf(4096), fixedHeaderByte, new ParseContext());
                });
        assertTrue(thrown.getMessage().contains("MqttBrokerSession unsupport ControlPacketType [" + fixedHeaderByte + "]"));
    }
}
