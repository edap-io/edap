package io.edap.mqtt.broker;

import io.edap.Decoder;
import io.edap.buffer.FastBuf;
import io.edap.mqtt.wire.Connect;
import io.edap.mqtt.wire.ControlPacket;
import io.edap.mqtt.wire.ProtocolLevel;
import io.edap.nio.ParseResult;

import java.nio.charset.StandardCharsets;

public class MqttBaseDecoder implements Decoder<ControlPacket, MqttBrokerSession> {

    static final int CONNECT_TYPE_VALUE = 1;

    static final int CONN_PAYLOAD_FORMAT_INDICATOR = 1;
    static final int CONN_MESSAGE_EXPIRY_INTERVAL = 2;
    static final int CONN_CONTENT_TYPE = 3;
    static final int CONN_RESPONSE_TOPIC = 8;
    static final int CONN_CORRELATION_DATA = 9;
    static final int CONN_PROP_SESSION_EXPIRY_INTERVAL = 17;
    static final int CONN_PROP_AUTH_METHOD = 21;
    static final int CONN_PROP_AUTH_DATA = 22;
    static final int CONN_PROP_REQUEST_PROBLEM_INFO = 23;
    static final int CONN_PROP_WILL_DELAY_INTERVAL = 24;
    static final int CONN_PROP_REQUEST_RESPONSE = 25;
    static final int CONN_PROP_RECEIVE_MAXIMUM = 33;
    static final int CONN_PROP_TOPIC_ALIAS_MAXIMUM = 34;
    static final int CONN_PROP_USER_PROPERTY = 38;
    static final int CONN_PROP_MAX_PACKET_SIZE = 39;

    @Override
    public ParseResult<ControlPacket> decode(FastBuf bufIn, MqttBrokerSession nioSession) {
        FastBuf _buf = bufIn;
        long rpos = _buf.rpos();
        int fixedHeader = _buf.get(rpos) & 0xFF;
        int typeValue = fixedHeader >> 4;
        if (typeValue == CONNECT_TYPE_VALUE) {
            return parseConnect(_buf, fixedHeader);
        }
        System.out.println("fixedHeader=" + (fixedHeader >> 4));
        return null;
    }

    private ParseResult<ControlPacket> parseConnect(FastBuf bufIn, int fixedHeaderByte) {
        ParseResult<ControlPacket> r = new ParseResult<>();
        FastBuf _buf = bufIn;
        long rpos = _buf.rpos();
        int remainBytes = _buf.remain();
        if (remainBytes < 5) {
            r.setFinished(false);
            return r;
        }
        rpos++;
        Connect connect = new Connect(fixedHeaderByte);
        int remain;
        int varFirst = _buf.get(rpos++) & 0xFF;
        if (varFirst >= 0) {
            remain = varFirst;
        } else {
            int varTwo = _buf.get(rpos++);
            if (varTwo > 0) {
                remain = (varFirst & 0x7F) << 7 | varTwo;
            } else {
                int varThree = _buf.get(rpos++);
                if (varThree > 0) {
                    remain = (varFirst & 0x7F) << 14 | (varTwo & 0x7F) << 7 | varThree;
                } else {
                    remain = (varFirst & 0x7F) << 21 | (varTwo & 0x7F) << 14 | (varThree & 0x7F) << 7 | _buf.get(rpos++);
                }
            }
        }
        remainBytes = (int)(_buf.limit() - rpos);
        if (remainBytes >= remain) {
            int len = _buf.get(rpos++) << 8 | _buf.get(rpos++);
            byte[] data = new byte[len];
            _buf.get(rpos, data);
            rpos += len;
            connect.setProtocolName(new String(data));
            connect.setProtocolLevel(ProtocolLevel.fromValue(_buf.get(rpos++)));
            int flag = _buf.get(rpos++) & 0xFF;
            connect.setUserNameFlag(flag >> 7);
            connect.setPasswordFlag((flag & 0x7F) >> 6);
            connect.setWillRetain((flag & 0x3F) >> 5);
            connect.setWillQoS((flag & 0x1F) >> 3);
            connect.setWillFlag((flag & 0x7) >> 2);
            connect.setCleanSessionFlag((flag & 0x3) >> 1);
            connect.setReserved((flag & 0x1));

            int keepAlive = (_buf.get(rpos++) & 0xFF) << 8 | _buf.get(rpos++) & 0xFF;
            connect.setKeepAlive(keepAlive);

            if (connect.getProtocolLevel().getValue() > 4) {
                rpos = parseConnectHeaderProperty(_buf, connect, rpos);
            }

            len = _buf.get(rpos++) << 8 | _buf.get(rpos++);
            data = new byte[len];
            _buf.get(rpos, data);
            rpos += len;
            connect.setClientIdentifier(new String(data, StandardCharsets.UTF_8));
            if (rpos < _buf.limit() && connect.getWillFlag() > 0 && connect.getProtocolLevel().getValue() > 4) {
                parseConnectPayloadProperty(_buf, connect, rpos);
            }

            if (rpos < _buf.limit()  && connect.getWillFlag() > 0) {
                len = _buf.get(rpos++) << 8 | _buf.get(rpos++);
                data = new byte[len];
                _buf.get(rpos, data);
                rpos += len;
                connect.setTopic(new String(data, StandardCharsets.UTF_8));
                len = _buf.get(rpos++) << 8 | _buf.get(rpos++);
                data = new byte[len];
                _buf.get(rpos, data);
                rpos += len;
                if (connect.getProtocolLevel().getValue() < 5) {
                    connect.setMessage(new String(data, StandardCharsets.UTF_8));
                } else {
                    connect.setPayload(data);
                }
            }

            if (rpos < _buf.limit()  && connect.getUserNameFlag() > 0) {
                len = _buf.get(rpos++) << 8 | _buf.get(rpos++);
                data = new byte[len];
                _buf.get(rpos, data);
                rpos += len;
                connect.setUserName(new String(data, StandardCharsets.UTF_8));
            }
            if (rpos < _buf.limit() && connect.getPasswordFlag() > 0) {
                len = _buf.get(rpos++) << 8 | _buf.get(rpos++);
                data = new byte[len];
                _buf.get(rpos, data);
                rpos += len;
                connect.setPassword(new String(data, StandardCharsets.UTF_8));
            }
            _buf.rpos(rpos);
        } else {
            r.setFinished(false);
        }
        r.setMessage(connect);
        return r;
    }

    private long parseConnectPayloadProperty(FastBuf bufIn, Connect connect, long rpos) {
        FastBuf _buf = bufIn;
        int remain;
        int varFirst = _buf.get(rpos++) & 0xFF;
        if (varFirst >= 0) {
            remain = varFirst;
        } else {
            int varTwo = _buf.get(rpos++);
            if (varTwo > 0) {
                remain = (varFirst & 0x7F) << 7 | varTwo;
            } else {
                int varThree = _buf.get(rpos++);
                if (varThree > 0) {
                    remain = (varFirst & 0x7F) << 14 | (varTwo & 0x7F) << 7 | varThree;
                } else {
                    remain = (varFirst & 0x7F) << 21 | (varTwo & 0x7F) << 14 | (varThree & 0x7F) << 7 | _buf.get(rpos++);
                }
            }
        }
        if (remain <= 0) {
            return rpos;
        }
        long end = rpos + remain;
        while (rpos < end) {
            int key = _buf.get(rpos++) & 0xFF;
            switch (key) {
                case CONN_PROP_WILL_DELAY_INTERVAL:
                    connect.setWillDelayInterval(
                                    (_buf.get(rpos++) & 0xFF) << 24 |
                                    (_buf.get(rpos++) & 0xFF) << 16 |
                                    (_buf.get(rpos++) & 0xFF) << 8 |
                                    _buf.get(rpos++) & 0xFF);
                    break;
                case CONN_PAYLOAD_FORMAT_INDICATOR:
                    connect.setPayloadFormatIndicator(_buf.get(rpos++) & 0xFF);
                    break;
                case CONN_MESSAGE_EXPIRY_INTERVAL:
                    connect.setMessageExpiryInterval(
                                    (_buf.get(rpos++) & 0xFF) << 24 |
                                    (_buf.get(rpos++) & 0xFF) << 16 |
                                    (_buf.get(rpos++) & 0xFF) << 8 |
                                    _buf.get(rpos++) & 0xFF);
                    break;
                case CONN_CONTENT_TYPE:
                    int len = (_buf.get(rpos++) & 0xFF) << 8 | _buf.get(rpos++) & 0xFF;
                    byte[] data = new byte[len];
                    rpos += _buf.get(rpos, data);
                    connect.setContentType(new String(data, StandardCharsets.UTF_8));
                    break;
                case CONN_RESPONSE_TOPIC:
                    len = (_buf.get(rpos++) & 0xFF) << 8 | _buf.get(rpos++) & 0xFF;
                    data = new byte[len];
                    rpos += _buf.get(rpos, data);
                    connect.setResponseTopic(new String(data, StandardCharsets.UTF_8));
                    break;
                case CONN_CORRELATION_DATA:
                    len = (_buf.get(rpos++) & 0xFF) << 8 | _buf.get(rpos++) & 0xFF;
                    data = new byte[len];
                    rpos += _buf.get(rpos, data);
                    connect.setCorrelationData(data);
                    break;
                case CONN_PROP_USER_PROPERTY:
                    len = (_buf.get(rpos++) & 0xFF) << 8 | _buf.get(rpos++) & 0xFF;
                    data = new byte[len];
                    rpos += _buf.get(rpos, data);
                    String k = new String(data, StandardCharsets.UTF_8);
                    len = (_buf.get(rpos++) & 0xFF) << 8 | _buf.get(rpos++) & 0xFF;
                    data = new byte[len];
                    rpos += _buf.get(rpos, data);
                    String v = new String(data, StandardCharsets.UTF_8);
                    connect.putUserProperty(k, v);
                    break;
            }
        }
        return rpos;
    }

    private long parseConnectHeaderProperty(FastBuf bufIn, Connect connect, long rpos) {
        FastBuf _buf = bufIn;
        int remain;
        int varFirst = _buf.get(rpos++) & 0xFF;
        if (varFirst >= 0) {
            remain = varFirst;
        } else {
            int varTwo = _buf.get(rpos++);
            if (varTwo > 0) {
                remain = (varFirst & 0x7F) << 7 | varTwo;
            } else {
                int varThree = _buf.get(rpos++);
                if (varThree > 0) {
                    remain = (varFirst & 0x7F) << 14 | (varTwo & 0x7F) << 7 | varThree;
                } else {
                    remain = (varFirst & 0x7F) << 21 | (varTwo & 0x7F) << 14 | (varThree & 0x7F) << 7 | _buf.get(rpos++);
                }
            }
        }
        if (remain <= 0) {
            return rpos;
        }
        long end = rpos + remain;
        while (rpos < end) {
            int key = _buf.get(rpos++) & 0xFF;
            switch (key) {
                case CONN_PROP_SESSION_EXPIRY_INTERVAL:
                    connect.setSessionExpiryInterval(
                                    (_buf.get(rpos++) & 0xFF) << 24 |
                                    (_buf.get(rpos++) & 0xFF) << 16 |
                                    (_buf.get(rpos++) & 0xFF) << 8 |
                                    _buf.get(rpos++) & 0xFF);
                    break;
                case CONN_PROP_RECEIVE_MAXIMUM:
                    connect.setReceiveMaximum((_buf.get(rpos++) & 0xFF) << 8 | _buf.get(rpos++) & 0xFF);
                    break;
                case CONN_PROP_MAX_PACKET_SIZE:
                    connect.setMaximumPacketSize(
                                    (_buf.get(rpos++) & 0xFF) << 24 |
                                    (_buf.get(rpos++) & 0xFF) << 16 |
                                    (_buf.get(rpos++) & 0xFF) << 8 |
                                    _buf.get(rpos++) & 0xFF);
                    break;
                case CONN_PROP_TOPIC_ALIAS_MAXIMUM:
                    connect.setTopicAliasMaximum((_buf.get(rpos++) & 0xFF) << 8 | _buf.get(rpos++) & 0xFF);
                    break;
                case CONN_PROP_REQUEST_RESPONSE:
                    connect.setRequestResponse(_buf.get(rpos++) & 0xFF);
                    break;
                case CONN_PROP_REQUEST_PROBLEM_INFO:
                    connect.setRequestProblemInfo(_buf.get(rpos++) & 0xFF);
                    break;
                case CONN_PROP_USER_PROPERTY:
                    int len = (_buf.get(rpos++) & 0xFF) << 8 | _buf.get(rpos++) & 0xFF;
                    byte[] data = new byte[len];
                    rpos += _buf.get(rpos, data);
                    String k = new String(data, StandardCharsets.UTF_8);
                    len = (_buf.get(rpos++) & 0xFF) << 8 | _buf.get(rpos++) & 0xFF;
                    data = new byte[len];
                    rpos += _buf.get(rpos, data);
                    String v = new String(data, StandardCharsets.UTF_8);
                    connect.putHeaderUserProperty(k, v);
                    break;
                case CONN_PROP_AUTH_METHOD:
                    len = (_buf.get(rpos++) & 0xFF) << 8 | _buf.get(rpos++) & 0xFF;
                    data = new byte[len];
                    rpos += _buf.get(rpos, data);
                    connect.setAuthMethod(new String(data, StandardCharsets.UTF_8));
                    break;
                case CONN_PROP_AUTH_DATA:
                    len = (_buf.get(rpos++) & 0xFF) << 8 | _buf.get(rpos++) & 0xFF;
                    data = new byte[len];
                    rpos += _buf.get(rpos, data);
                    connect.setAuthData(data);
                    break;
                default:
                    break;
            }
        }
        return rpos;
    }

    @Override
    public void reset() {

    }
}
