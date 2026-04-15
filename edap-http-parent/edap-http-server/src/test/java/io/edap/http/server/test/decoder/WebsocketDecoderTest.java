package io.edap.http.server.test.decoder;

import io.edap.buffer.FastBuf;
import io.edap.http.HttpDecoder;
import io.edap.http.server.HttpServerNioSession;
import io.edap.http.server.WebsocketDecoder;
import io.edap.http.ws.*;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

public class WebsocketDecoderTest {

    @Test
    public void testDecodeOnlyOpcode() throws Exception {
        WebsocketDecoder decoder = new WebsocketDecoder();
        FastBuf buf = new FastBuf(4096);
        HttpServerNioSession session = new HttpServerNioSession();
        buf.write((byte)Integer.parseInt("11110001", 2));
        AbstractFrame frame = decoder.decode(buf, session);
        assertNull(frame);
        frame = session.getTmpWSFrame();
        assertNotNull(frame);
        assertTrue(frame instanceof TextFrame);

        session.setTmpWSFrame(null);
        session.setWsState(null);
        buf.write((byte)Integer.parseInt("11110010", 2));
        frame = decoder.decode(buf, session);
        assertNull(frame);
        frame = session.getTmpWSFrame();
        assertNotNull(frame);
        assertTrue(frame instanceof BinaryFrame);

        session.setTmpWSFrame(null);
        session.setWsState(null);
        buf.write((byte)Integer.parseInt("11110011", 2));
        frame = decoder.decode(buf, session);
        assertNull(frame);
        frame = session.getTmpWSFrame();
        assertNull(frame);

        buf.clear();
        session.setTmpWSFrame(null);
        session.setWsState(null);
        String opstr = Integer.toString(8, 2);
        opstr = opstr.substring(opstr.length() - 4, opstr.length());
        buf.write((byte)Integer.parseInt("1111" + opstr, 2));
        frame = decoder.decode(buf, session);
        assertTrue(frame instanceof CloseFrame);

        buf.clear();
        session.setTmpWSFrame(null);
        session.setWsState(null);
        opstr = Integer.toString(9, 2);
        opstr = opstr.substring(opstr.length() - 4, opstr.length());
        buf.write((byte)Integer.parseInt("1111" + opstr, 2));
        frame = decoder.decode(buf, session);
        assertTrue(frame instanceof Ping);

        buf.clear();
        session.setTmpWSFrame(null);
        session.setWsState(null);
        opstr = Integer.toString(10, 2);
        opstr = opstr.substring(opstr.length() - 4, opstr.length());
        buf.write((byte)Integer.parseInt("1111" + opstr, 2));
        frame = decoder.decode(buf, session);
        assertTrue(frame instanceof Pong);
    }

    @Test
    public void testDecodePayloadLength() throws Exception {

        WebsocketDecoder decoder = new WebsocketDecoder();
        FastBuf buf = new FastBuf(4096);
        HttpServerNioSession session = new HttpServerNioSession();
        String opstr = Integer.toString(1, 2);
        opstr = getRightStr(opstr, 4);
        byte[] data = new byte[2];
        data[0] = (byte)Integer.parseInt("1111" + opstr, 2);
        String payloadLenStr = Integer.toString(1, 2);
        payloadLenStr = getRightStr(payloadLenStr, 7);
        data[1] = (byte)Integer.parseInt("1" + payloadLenStr, 2);;
        buf.write(data);

        AbstractFrame frame = decoder.decode(buf, session);
        assertNull(frame);
        frame = session.getTmpWSFrame();
        assertNotNull(frame);
        assertTrue(frame instanceof TextFrame);
        assertTrue(frame.getPayloadLength() == 1);
        assertEquals(session.getWsState(), HttpDecoder.WSState.MASK_KEY);


        session.setTmpWSFrame(null);
        session.setWsState(null);
        payloadLenStr = Integer.toString(125, 2);
        payloadLenStr = getRightStr(payloadLenStr, 7);
        data[1] = (byte)Integer.parseInt("1" + payloadLenStr, 2);;
        buf.write(data);

        frame = decoder.decode(buf, session);
        assertNull(frame);
        frame = session.getTmpWSFrame();
        assertNotNull(frame);
        assertTrue(frame instanceof TextFrame);
        assertTrue(frame.getPayloadLength() == 125);
        assertEquals(session.getWsState(), HttpDecoder.WSState.MASK_KEY);

        session.setTmpWSFrame(null);
        session.setWsState(null);
        payloadLenStr = Integer.toString(126, 2);
        payloadLenStr = getRightStr(payloadLenStr, 7);
        data[1] = (byte)Integer.parseInt("1" + payloadLenStr, 2);;
        buf.write(data);

        frame = decoder.decode(buf, session);
        assertNull(frame);
        frame = session.getTmpWSFrame();
        assertNotNull(frame);
        assertTrue(frame instanceof TextFrame);
        assertTrue(frame.getPayloadLength() == 126);
        assertEquals(session.getWsState(), HttpDecoder.WSState.PAYLOAD_LENGTH_EXTEND);

        session.setTmpWSFrame(null);
        session.setWsState(null);
        payloadLenStr = Integer.toString(127, 2);
        payloadLenStr = getRightStr(payloadLenStr, 7);
        data[1] = (byte)Integer.parseInt("1" + payloadLenStr, 2);;
        buf.write(data);

        frame = decoder.decode(buf, session);
        assertNull(frame);
        frame = session.getTmpWSFrame();
        assertNotNull(frame);
        assertTrue(frame instanceof TextFrame);
        assertTrue(frame.getPayloadLength() == 127);
        assertEquals(session.getWsState(), HttpDecoder.WSState.PAYLOAD_LENGTH_EXTEND);

        session.setTmpWSFrame(null);
        session.setWsState(null);
        payloadLenStr = Integer.toString(125, 2);
        payloadLenStr = getRightStr(payloadLenStr, 7);
        data[1] = (byte)Integer.parseInt("0" + payloadLenStr, 2);;
        buf.write(data);

        frame = decoder.decode(buf, session);
        assertNull(frame);
        frame = session.getTmpWSFrame();
        assertNotNull(frame);
        assertTrue(frame instanceof TextFrame);
        assertTrue(frame.getPayloadLength() == 125);
        assertEquals(session.getWsState(), HttpDecoder.WSState.PAYLOAD);
    }

    @Test
    public void testDecodeFullFrame() throws Exception {
        WebsocketDecoder decoder = new WebsocketDecoder();
        FastBuf buf = new FastBuf(4096);
        HttpServerNioSession session = new HttpServerNioSession();
        String opstr = Integer.toString(1, 2);
        opstr = getRightStr(opstr, 4);
        byte[] data = new byte[3];
        byte d = (byte)new Random().nextInt(Byte.MAX_VALUE);
        data[0] = (byte)Integer.parseInt("1111" + opstr, 2);
        String payloadLenStr = Integer.toString(1, 2);
        payloadLenStr = getRightStr(payloadLenStr, 7);
        data[1] = (byte)Integer.parseInt("0" + payloadLenStr, 2);
        data[2] = d;
        buf.write(data);

        AbstractFrame frame = decoder.decode(buf, session);
        assertNotNull(frame);

        int mastVal = new Random().nextInt();
        data = new byte[7];
        data[0] = (byte)Integer.parseInt("1111" + opstr, 2);
        data[1] = (byte)Integer.parseInt("1" + payloadLenStr, 2);
        data[2] = (byte)((mastVal >> 24) & 0xFF);
        data[3] = (byte)((mastVal >> 16) & 0xFF);
        data[4] = (byte)((mastVal >> 86) & 0xFF);
        data[5] = (byte)(mastVal & 0xFF);
        data[6] = (byte)(d ^ data[2]);
        buf.write(data);

        frame = decoder.decode(buf, session);
        assertNotNull(frame);
        assertArrayEquals(frame.getPayload(), new byte[]{d});


        Random random = new Random();
        mastVal = random.nextInt();
        int payloadLen = 126 + new Random().nextInt(Byte.MAX_VALUE);
        byte[] payload = new byte[payloadLen];
        byte[] payloadOrignal = new byte[payloadLen];
        for (int i=0;i<payloadLen;i++) {
            byte b = (byte)random.nextInt(Byte.MAX_VALUE);
            payload[i] = b;
            payloadOrignal[i] = b;
        }
        payloadLenStr = Integer.toString(126, 2);
        payloadLenStr = getRightStr(payloadLenStr, 7);
        data = new byte[8 + payloadLen];
        data[0] = (byte)Integer.parseInt("1111" + opstr, 2);
        data[1] = (byte)Integer.parseInt("1" + payloadLenStr, 2);
        data[2] = (byte)((payloadLen >> 8) & 0xFF);
        data[3] = (byte)((payloadLen     ) & 0xFF);
        data[4] = (byte)((mastVal >> 24) & 0xFF);
        data[5] = (byte)((mastVal >> 16) & 0xFF);
        data[6] = (byte)((mastVal >> 8 ) & 0xFF);
        data[7] = (byte)(mastVal & 0xFF);
        for (int i=0;i<payloadLen;i++) {
            payload[i] = (byte)(payload[i] ^ data[(i%4) + 4]);
        }
        System.arraycopy(payload, 0, data, 8, payload.length);
        buf.write(data);

        frame = decoder.decode(buf, session);
        assertNotNull(frame);
        assertArrayEquals(frame.getPayload(), payloadOrignal);


        mastVal = random.nextInt();
        payloadLen = Short.MAX_VALUE * 2 + new Random().nextInt(Short.MAX_VALUE);
        payload = new byte[payloadLen];
        payloadOrignal = new byte[payloadLen];
        for (int i=0;i<payloadLen;i++) {
            byte b = (byte)random.nextInt(Byte.MAX_VALUE);
            payload[i] = b;
            payloadOrignal[i] = b;
        }
        payloadLenStr = Integer.toString(127, 2);
        payloadLenStr = getRightStr(payloadLenStr, 7);
        data = new byte[14 + payloadLen];
        data[0] = (byte)Integer.parseInt("1111" + opstr, 2);
        data[1] = (byte)Integer.parseInt("1" + payloadLenStr, 2);
        data[2] = (byte)(((long)payloadLen >> 56) & 0xFFL);
        data[3] = (byte)(((long)payloadLen >> 48) & 0xFFL);
        data[4] = (byte)(((long)payloadLen >> 40) & 0xFFL);
        data[5] = (byte)(((long)payloadLen >> 32) & 0xFFL);
        data[6] = (byte)(((long)payloadLen >> 24) & 0xFFL);
        data[7] = (byte)(((long)payloadLen >> 16) & 0xFFL);
        data[8] = (byte)(((long)payloadLen >> 8) & 0xFFL);
        data[9] = (byte)(((long)payloadLen     ) & 0xFFL);

        data[10] = (byte)((mastVal >> 24) & 0xFF);
        data[11] = (byte)((mastVal >> 16) & 0xFF);
        data[12] = (byte)((mastVal >> 8 ) & 0xFF);
        data[13] = (byte)(mastVal & 0xFF);
        for (int i=0;i<payloadLen;i++) {
            payload[i] = (byte)(payload[i] ^ data[(i%4) + 10]);
        }
        System.arraycopy(payload, 0, data, 14, payload.length);
        buf.clear();
        if (buf.writeRemain() < data.length) {
            buf = new FastBuf(data.length);
        }
        buf.clear();
        buf.write(data);

        frame = decoder.decode(buf, session);
        assertNotNull(frame);
        assertArrayEquals(frame.getPayload(), payloadOrignal);
    }

    private String getRightStr(String text, int len) {
        if (text.length() >= len) {
            return text.substring(text.length() - len, text.length());
        } else {
            int left = len - text.length();
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < left; i++) {
                sb.append('0');
            }
            sb.append(text);
            return sb.toString();
        }
    }
}
