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
        frame.setMasked(masked);
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
            frame.setMaskingKey(mask);
        }

        if (buf.remain() < payloadLength) {
            ByteData tmpData = session.getTmpData();
            byte[] data = tmpData.getBytes();
            if (data.length < buf.remain()) {
                byte[] tmp = new byte[buf.remain()];
                if (tmpData.getLength() > 0) {
                    System.arraycopy(tmpData.getBytes(), 0, tmp, 0, tmpData.getLength());
                }
                tmpData.setBytes(tmp);
                data = tmp;
            }
            int len = buf.get(data);
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
                            // 长度扩展字节已经消费掉，payload 累积缓冲必须从 0 开始，
                            // 否则这些长度字节会被当成 payload 的头部（帧内容错乱）。
                            data.setLength(0);
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
                                // 8 字节扩展长度需要写入 [len, 8) 区间，缓冲不足 8 字节时先扩容，
                                // 否则 FastBuf.get(byte[],start,len) 越界写会破坏堆。
                                if (data.getBytes().length < 8) {
                                    byte[] tmp = new byte[8];
                                    System.arraycopy(data.getBytes(), 0, tmp, 0, len);
                                    data.setBytes(tmp);
                                }
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
                                // 长度扩展字节已消费，payload 累积缓冲从 0 开始（见 126 分支的说明）
                                data.setLength(0);
                                if (frame.isMasked()) {
                                    wsState = HttpDecoder.WSState.MASK_KEY;
                                } else {
                                    wsState = HttpDecoder.WSState.PAYLOAD;
                                }
                                // 必须写回 session：状态机跨 decode() 调用推进，
                                // 只改局部变量的话下一次 decode() 会退回 PAYLOAD_LENGTH_EXTEND 重新解析长度。
                                session.setWsState(wsState);
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
                                // 同上：状态必须写回 session，否则跨 decode() 调用会退回长度扩展状态
                                session.setWsState(wsState);
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
                            // mask 字节已消费完，payload 累积缓冲从 0 开始，否则 mask 字节会污染 payload
                            data.setLength(0);
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
                            // mask key 需要 4 字节空间，缓冲不足时先扩容，避免越界写
                            if (data.getBytes().length < 4) {
                                data.setBytes(new byte[4]);
                            }
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
