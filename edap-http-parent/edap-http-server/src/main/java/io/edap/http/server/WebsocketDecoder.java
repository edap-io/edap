package io.edap.http.server;

import io.edap.buffer.FastBuf;
import io.edap.http.HttpDecoder;
import io.edap.http.ws.*;
import io.edap.util.ByteData;

import static io.edap.http.ws.AbstractFrame.*;

public class WebsocketDecoder {

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
                                (buf.get() & 0xffl);
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
        AbstractFrame frame = session.getTmpWSFrame();
        if (buf.remain() < 1) {
            return null;
        }
        HttpDecoder.WSState wsState = session.getWsState();
        ByteData data = session.getTmpData();
        long payloadLength;
        int remain = buf.remain();
        while (remain > 0) {
            switch (wsState) {
                case PAYLOAD_LENGTH:
                    int second = buf.get() & 0xff;
                    boolean masked = (second & 0x80) != 0;
                    payloadLength = second & 0x7f;
                    frame.setPayloadLength(payloadLength);
                    frame.setMasked(masked);
                    if (payloadLength < 126) {
                        if (masked) {
                            wsState = HttpDecoder.WSState.MASK_KEY;
                        } else {
                            wsState = HttpDecoder.WSState.PAYLOAD;
                        }
                        session.setWsState(wsState);
                    } else {
                        wsState = HttpDecoder.WSState.PAYLOAD_LENGTH_EXTEND;
                        session.setWsState(wsState);
                    }
                    remain = buf.remain();
                    break;
                case PAYLOAD_LENGTH_EXTEND:
                    if (frame.getPayloadLength() == 126) {
                        if (data.getLength() > 0) {
                            payloadLength = (data.getBytes()[0] & 0xff) << 8 | buf.get() & 0xff;
                            frame.setPayloadLength(payloadLength);
                            if (frame.isMasked()) {
                                wsState = HttpDecoder.WSState.MASK_KEY;
                            } else {
                                wsState = HttpDecoder.WSState.PAYLOAD;
                            }
                            session.setWsState(wsState);
                        } else {
                            if (remain > 1) {
                                payloadLength = (buf.get() & 0xff) << 8 | buf.get() & 0xff;
                                frame.setPayloadLength(payloadLength);

                                if (frame.isMasked()) {
                                    wsState = HttpDecoder.WSState.MASK_KEY;
                                } else {
                                    wsState = HttpDecoder.WSState.PAYLOAD;
                                }
                                session.setWsState(wsState);
                            } else {
                                data.setBytes(new byte[]{buf.get()});
                                data.setLength(1);
                            }
                            remain = buf.remain();
                        }
                    } else {
                        int len = data.getLength();
                        if (len > 0) {
                            if (remain + len > 7) {
                                buf.get(data.getBytes(), len, 8 - len);
                                payloadLength =
                                        (data.getBytes()[0] & 0xffl) << 56 |
                                        (data.getBytes()[1] & 0xffl) << 48 |
                                        (data.getBytes()[2] & 0xffl) << 40 |
                                        (data.getBytes()[3] & 0xffl) << 32 |
                                        (data.getBytes()[4] & 0xffl) << 24 |
                                        (data.getBytes()[5] & 0xffl) << 16 |
                                        (data.getBytes()[6] & 0xffl) <<  8 |
                                        (data.getBytes()[7] & 0xffl);
                                frame.setPayloadLength(payloadLength);
                                if (frame.isMasked()) {
                                    wsState = HttpDecoder.WSState.MASK_KEY;
                                } else {
                                    wsState = HttpDecoder.WSState.PAYLOAD;
                                }
                            } else {
                                len += buf.get(data.getBytes(), len, 8);
                                data.setLength(len);
                            }
                            remain = buf.remain();
                        } else {
                            if (remain > 7) {
                                payloadLength =
                                        (buf.get() & 0xffl) << 56 |
                                        (buf.get() & 0xffl) << 48 |
                                        (buf.get() & 0xffl) << 40 |
                                        (buf.get() & 0xffl) << 32 |
                                        (buf.get() & 0xffl) << 24 |
                                        (buf.get() & 0xffl) << 16 |
                                        (buf.get() & 0xffl) <<  8 |
                                        (buf.get() & 0xffl);
                                frame.setPayloadLength(payloadLength);
                                if (frame.isMasked()) {
                                    wsState = HttpDecoder.WSState.MASK_KEY;
                                } else {
                                    wsState = HttpDecoder.WSState.PAYLOAD;
                                }

                            } else {
                                if (data.getBytes().length < 8) {
                                    data.setBytes(new byte[8]);
                                }
                                len = buf.get(data.getBytes());
                                data.setLength(len);
                            }
                            remain = buf.remain();
                        }
                    }
                    break;
                case MASK_KEY:
                    int len = data.getLength();
                    if (len > 0) {
                        if (remain + len > 3) {
                            byte[] maskKey = new byte[4];
                            System.arraycopy(data.getBytes(), 0, maskKey, 0, len);
                            buf.get(maskKey, len, 4 -len);
                            frame.setMaskingKey(maskKey);
                            wsState = HttpDecoder.WSState.PAYLOAD;
                            session.setWsState(wsState);
                        } else {
                            len += buf.get(data.getBytes(), len);
                            data.setLength(len);
                        }
                    } else {
                        if (remain > 3) {
                            byte[] maskKey = new byte[4];
                            buf.get(maskKey);
                            frame.setMaskingKey(maskKey);
                            wsState = HttpDecoder.WSState.PAYLOAD;
                            session.setWsState(wsState);
                        } else {
                            len += buf.get(data.getBytes(),  len, 4);
                            data.setLength(len);
                        }
                    }
                    remain = buf.remain();
                    break;
                case PAYLOAD:
                    len = data.getLength();
                    int payloadIntLen = (int)frame.getPayloadLength();
                    if (len > 0) {
                        if (len + remain >= payloadIntLen) {
                            byte[] payload = new byte[payloadIntLen];
                            System.arraycopy(data.getBytes(), 0, payload, 0, len);
                            buf.get(payload, len, payloadIntLen - len);
                            fillFramePayload(frame, payload, frame.getMaskingKey());
                            session.setWsState(HttpDecoder.WSState.OPCODE);
                            session.setTmpWSFrame(null);
                            session.getTmpData().setLength(0);
                            return frame;
                        } else {
                            byte[] ds = data.getBytes();
                            if (ds.length - data.getLength() < remain) {
                                byte[] nds = new byte[remain + data.getLength()];
                                System.arraycopy(ds, 0, nds, 0, len);
                                buf.get(nds, len, remain);
                                data.setBytes(nds);
                                data.setLength(len + remain);
                            } else {
                                len += buf.get(ds, len, remain);
                                data.setLength(len);
                            }
                            remain = buf.remain();
                        }
                    } else {
                        if (remain >= payloadIntLen) {
                            byte[] payload = new byte[payloadIntLen];
                            buf.get(payload, payloadIntLen);
                            fillFramePayload(frame, payload, frame.getMaskingKey());
                            session.setWsState(HttpDecoder.WSState.OPCODE);
                            session.setTmpWSFrame(null);
                            session.getTmpData().setLength(0);
                            return frame;
                        } else {
                            byte[] ds = data.getBytes();
                            if (ds.length < remain) {
                                ds = new byte[remain];
                                data.setBytes(ds);
                            }
                            data.setLength(buf.get(ds, remain));
                            remain = buf.remain();
                        }
                    }
                    break;
                default:
                    break;
            }
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
