package io.edap.http.server;

import io.edap.buffer.FastBuf;
import io.edap.http.HttpDecoder;
import io.edap.http.ws.*;
import io.edap.util.ByteData;

public class WebsocketDecoder {

    static final byte TEXT_OPCODE   = 0x01;
    static final byte BINARY_OPCODE = 0x02;
    static final byte CLOSE_OPCODE  = 0x08;
    static final byte PING_OPCODE   = 0x09;
    static final byte PONG_OPCODE   = 0x0a;

    static final int MASK_KEY_LEN = 4;



    public AbstractFrame decode(FastBuf buf, HttpServerNioSession session) {
        HttpDecoder.WSState wsState = session.getWsState();
        if (wsState != null && wsState != HttpDecoder.WSState.OPCODE) {
            return decodeIncomplete(buf, session);
        }
        AbstractFrame frame;
        int     first  = buf.get() & 0xff;
        boolean fin    = (first & 0x80) != 0;
        byte    rsv    = (byte)((first & 0x70) >> 4);
        byte    opcode = (byte)(first & 0x0f);
        frame = initFrame(opcode);
        if (frame == null) {
            return frame;
        }
        frame.setFin(fin);
        if (buf.remain() < 1) {
            if (opcode == PING_OPCODE || opcode == PONG_OPCODE || opcode == CLOSE_OPCODE) {
                return frame;
            } else {
                wsState = HttpDecoder.WSState.PAYLOAD_LENGTH;
                session.setWsState(wsState);
                session.setTmpWSFrame(frame);
                return null;
            }
        }

        int     second = buf.get() & 0xff;
        boolean masked = (second & 0x80) != 0;
        long    payloadLength = second & 0x7f;
        frame.setRsv(rsv);
        frame.setPayloadLength(payloadLength);
        if (payloadLength == 126) {
            if (buf.remain() < 2) {
                ByteData tmpData = session.getTmpData();
                int len = buf.get(tmpData.getBytes());
                tmpData.setLength(len);
                wsState = HttpDecoder.WSState.PAYLOAD_LENGTH_EXTEND;
                session.setWsState(wsState);
                session.setTmpWSFrame(frame);
                return null;
            } else {
                payloadLength = (buf.get() & 0xff) << 8 | buf.get() & 0xff;
                frame.setPayloadLength(payloadLength);
            }
        } else if (payloadLength == 127) {
            if (buf.remain() < 8) {
                frame.setPayloadLength(payloadLength);
                ByteData tmpData = session.getTmpData();
                int len = buf.get(tmpData.getBytes());
                tmpData.setLength(len);
                wsState = HttpDecoder.WSState.PAYLOAD_LENGTH_EXTEND;
                session.setWsState(wsState);
                session.setTmpWSFrame(frame);
                return null;
            } else {
                payloadLength = (buf.get() & 0xffl) << 56 |
                                (buf.get() & 0xffl) << 48 |
                                (buf.get() & 0xffl) << 40 |
                                (buf.get() & 0xffl) << 32 |
                                (buf.get() & 0xffl) << 24 |
                                (buf.get() & 0xffl) << 16 |
                                (buf.get() & 0xffl) <<  8 |
                                (buf.get() & 0xffl) & 0xff;
                frame.setPayloadLength(payloadLength);
            }
        }

        byte[] mask = null;
        if (masked) {
            if (buf.remain() < MASK_KEY_LEN) {
                ByteData tmpData = session.getTmpData();
                int len = buf.get(tmpData.getBytes());
                tmpData.setLength(len);
                wsState = HttpDecoder.WSState.MASK_KEY;
                session.setWsState(wsState);
                session.setTmpWSFrame(frame);
                return null;
            }
            mask = new byte[MASK_KEY_LEN];
            buf.get(mask);
        }

        if (buf.remain() < payloadLength) {
            ByteData tmpData = session.getTmpData();
            int len = buf.get(tmpData.getBytes());
            tmpData.setLength(len);
            wsState = HttpDecoder.WSState.PAYLOAD;
            session.setWsState(wsState);
            session.setTmpWSFrame(frame);
            return null;
        } else {
            byte[] payload = getPayload(buf, (int)payloadLength);
            fillFramePayload(frame, payload, mask);
        }

        return frame;
    }

    private AbstractFrame decodeIncomplete(FastBuf buf, HttpServerNioSession session) {
        HttpDecoder.WSState wsState = session.getWsState();
        ByteData data = session.getTmpData();
        AbstractFrame frame = session.getTmpWSFrame();
        switch (wsState) {
            case PAYLOAD_LENGTH:


        }
        return null;
    }

    private byte[] getPayload(FastBuf buf, int payloadLength) {
        byte[] payload = new byte[payloadLength];
        buf.get(payload);
        return payload;
    }

    private AbstractFrame initFrame(byte opcode) {
        switch (opcode) {
            case CLOSE_OPCODE:
                return new CloseFrame();
            case PING_OPCODE:
                return new Ping();
            case PONG_OPCODE:
                return new Pong();
            case TEXT_OPCODE:
                return new TextFrame();
            case BINARY_OPCODE:
                return new BinaryFrame();
            default:
                return null;
        }
    }

    private void fillFramePayload(AbstractFrame frame, byte[] payload, byte[] maskKey) {
        if (maskKey != null && maskKey.length > 0) {
            for (int i = 0; i < payload.length; i++) {
                payload[i] = (byte) (payload[i] ^ maskKey[i % MASK_KEY_LEN]);
            }
        }
        frame.setPayload(payload);
    }
}
