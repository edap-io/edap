package io.edap.mqtt;

import io.edap.buffer.FastBuf;
import io.edap.mqtt.property.*;
import io.edap.nio.ParseResult;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

import static io.edap.mqtt.PacketProperty.*;
import static io.edap.mqtt.PropertyType.*;

public interface MqttPacketDecoder<ControlPacket> {

    ParseResult<ControlPacket> parse(FastBuf buf, int fixedHeaderByte, ParseContext parseContext);

    static int parseRemain(FastBuf buf, ParseContext parseContext) {
        int remain;
        long rpos  = parseContext.getRpos();
        long limit = buf.limit();
        byte b1 = buf.get(rpos++);
        if (b1 >= 0) {
            remain = b1;
        } else {
            if (rpos >= limit) {
                return -1;
            }
            byte b2 = buf.get(rpos++);
            if (b2 > 0) {
                remain = (b2 & 0x7F) << 7 | (b1 & 0x7F);
            } else {
                if (rpos >= limit) {
                    return -1;
                }
                byte b3 = buf.get(rpos++);
                if (b3 > 0) {
                    remain = (b3 & 0x7F) << 14 | (b2 & 0x7F) << 7 | (b1 & 0x7F);
                } else {
                    if (rpos >= limit) {
                        return -1;
                    }
                    remain = (buf.get(rpos++) & 0x7F) << 21 | (b3 & 0x7F) << 14 | (b2 & 0x7F) << 7 | (b1 & 0x7F);
                }
            }
        }
        parseContext.setRpos(rpos);
        return remain;
    }

    default LinkedHashMap<PropertyType, PacketProperty> parseProperties(FastBuf bufIn, ParseContext parseContext) {
        LinkedHashMap<PropertyType, PacketProperty> props = new LinkedHashMap<>();
        FastBuf _buf = bufIn;
        long rpos = parseContext.getRpos();
        int remain = MqttPacketDecoder.parseRemain(_buf, parseContext);
        if (remain <= 0) {
            parseContext.setRpos(rpos + 1);
            return props;
        }
        rpos = parseContext.getRpos();
        long end = rpos + remain;
        while (rpos < end) {
            int key = _buf.get(rpos++) & 0xFF;
            switch (key) {
                case PAYLOAD_FORMAT_INDICATOR_ID:
                    PayloadFormatIndicator pfi = new PayloadFormatIndicator();
                    pfi.value(_buf.get(rpos++));
                    props.put(PAYLOAD_FORMAT_INDICATOR, pfi);
                    break;
                case MESSAGE_EXPIRY_INTERVAL_ID:
                    MessageExpiryInterval mei = new MessageExpiryInterval();
                    mei.value((_buf.get(rpos++) & 0xFF) << 24 |
                            (_buf.get(rpos++) & 0xFF) << 16 |
                            (_buf.get(rpos++) & 0xFF) << 8 |
                            _buf.get(rpos++) & 0xFF);
                    props.put(MESSAGE_EXPIRY_INTERVAL, mei);
                    break;
                case CONTENT_TYPE_ID:
                    ContentType ct = new ContentType();
                    int len = (_buf.get(rpos++) & 0xFF) << 8 | _buf.get(rpos++) & 0xFF;
                    byte[] data = parseContext.getParseData();
                    if (data.length < len) {
                        data = new byte[len];
                        parseContext.setParseData(data);
                    }
                    rpos += _buf.get(rpos, data, 0, len);
                    ct.value(new String(data, 0, len, StandardCharsets.UTF_8));
                    props.put(CONTENT_TYPE, ct);
                    break;
                case RESPONSE_TOPIC_ID:
                    ResponseTopic rt = new ResponseTopic();
                    len = (_buf.get(rpos++) & 0xFF) << 8 | _buf.get(rpos++) & 0xFF;
                    data = parseContext.getParseData();
                    if (data.length < len) {
                        data = new byte[len];
                        parseContext.setParseData(data);
                    }
                    rpos += _buf.get(rpos, data, 0, len);
                    rt.value(new String(data, 0, len, StandardCharsets.UTF_8));
                    props.put(RESPONSE_TOPIC, rt);
                    break;
                case CORRELATION_DATA_ID:
                    CorrelationData cd = new CorrelationData();
                    len = (_buf.get(rpos++) & 0xFF) << 8 | _buf.get(rpos++) & 0xFF;
                    data = new byte[len];
                    rpos += _buf.get(rpos, data);
                    cd.value(data);
                    props.put(CORRELATION_DATA, cd);
                    break;
                case SUBSCRIPTION_INDENTIFIER_ID:
                    SubscriptionIdentifier si = new SubscriptionIdentifier();
                    byte varFirst = _buf.get(rpos++);
                    if (varFirst >= 0) {
                        remain = varFirst;
                    } else {
                        int varTwo = _buf.get(rpos++);
                        if (varTwo > 0) {
                            remain = (varTwo & 0x7F) << 7 | (varFirst & 0x7F);
                        } else {
                            int varThree = _buf.get(rpos++);
                            if (varThree > 0) {
                                remain = (varThree & 0x7F) << 14 | (varTwo & 0x7F) << 7 | (varFirst & 0x7F);
                            } else {
                                remain = (_buf.get(rpos++) & 0x7F) << 21 | (varThree & 0x7F) << 14 | (varTwo & 0x7F) << 7 | (varFirst & 0x7F);
                            }
                        }
                    }
                    si.value(remain);
                    props.put(SUBSCRIPTION_INDENTIFIER, si);
                    break;
                case SESSION_EXPIRY_INTERVAL_ID:
                    SessionExpiryInterval sei = new SessionExpiryInterval();
                    sei.value((_buf.get(rpos++) & 0xFF) << 24 |
                            (_buf.get(rpos++) & 0xFF) << 16 |
                            (_buf.get(rpos++) & 0xFF) << 8 |
                            _buf.get(rpos++) & 0xFF);
                    props.put(SESSION_EXPIRY_INTERVAL, sei);
                    break;
                case ASSIGNED_CLIENT_IDENTIFIER_ID:
                    AssignedClientIdentifier aci = new AssignedClientIdentifier();
                    len = (_buf.get(rpos++) & 0xFF) << 8 | _buf.get(rpos++) & 0xFF;
                    data = parseContext.getParseData();
                    if (data.length < len) {
                        data = new byte[len];
                        parseContext.setParseData(data);
                    }
                    rpos += _buf.get(rpos, data, 0, len);
                    aci.value(new String(data, 0, len, StandardCharsets.UTF_8));
                    props.put(ASSIGNED_CLIENT_IDENTIFIER, aci);
                    break;
                case SERVER_KEEP_ALIVE_ID:
                    len = ((_buf.get(rpos++) & 0xFF) << 8 | _buf.get(rpos++) & 0xFF);
                    ServerKeepAlive ski = new ServerKeepAlive();
                    ski.value(len);
                    props.put(SERVER_KEEP_ALIVE, ski);
                    break;
                case AUTHENTICATION_METHOD_ID:
                    len = (_buf.get(rpos++) & 0xFF) << 8 | _buf.get(rpos++) & 0xFF;
                    data = parseContext.getParseData();
                    if (data.length < len) {
                        data = new byte[len];
                        parseContext.setParseData(data);
                    }
                    rpos += _buf.get(rpos, data, 0, len);
                    AuthenticationMethod am = new AuthenticationMethod();
                    am.value(new String(data, 0, len, StandardCharsets.UTF_8));
                    props.put(AUTHENTICATION_METHOD, am);
                    break;
                case AUTHENTICATION_DATA_ID:
                    len = (_buf.get(rpos++) & 0xFF) << 8 | _buf.get(rpos++) & 0xFF;
                    data = new byte[len];
                    rpos += _buf.get(rpos, data);
                    AuthenticationData ad = new AuthenticationData();
                    ad.value(data);
                    props.put(AUTHENTICATION_DATA, ad);
                    break;
                case REQUEST_PROBLEM_INFORMATION_ID:
                    RequestProblemInformation rpi = new RequestProblemInformation();
                    rpi.value(_buf.get(rpos++));
                    props.put(REQUEST_PROBLEM_INFORMATION, rpi);
                    break;
                case WILL_DELAY_INTERVAL_ID:
                    WillDelayInterval wdi = new WillDelayInterval();
                    wdi.value((_buf.get(rpos++) & 0xFF) << 24 |
                            (_buf.get(rpos++) & 0xFF) << 16 |
                            (_buf.get(rpos++) & 0xFF) << 8 |
                            _buf.get(rpos++) & 0xFF);
                    props.put(WILL_DELAY_INTERVAL, wdi);
                    break;
                case REQUEST_RESPONSE_INFORMATION_ID:
                    RequestResponseInformation rri = new RequestResponseInformation();
                    rri.value(_buf.get(rpos++));
                    props.put(REQUEST_RESPONSE_INFORMATION, rri);
                    break;
                case RESPONSE_INFORMATION_ID:
                    len = (_buf.get(rpos++) & 0xFF) << 8 | _buf.get(rpos++) & 0xFF;
                    data = parseContext.getParseData();
                    if (data.length < len) {
                        data = new byte[len];
                        parseContext.setParseData(data);
                    }
                    rpos += _buf.get(rpos, data, 0, len);
                    ResponseInformation ri = new ResponseInformation();
                    ri.value(new String(data, 0, len, StandardCharsets.UTF_8));
                    props.put(RESPONSE_INFORMATION, ri);
                    break;
                case SERVER_REFERENCE_ID:
                    len = (_buf.get(rpos++) & 0xFF) << 8 | _buf.get(rpos++) & 0xFF;
                    data = parseContext.getParseData();
                    if (data.length < len) {
                        data = new byte[len];
                        parseContext.setParseData(data);
                    }
                    rpos += _buf.get(rpos, data, 0, len);
                    ServerReference sr = new ServerReference();
                    sr.value(new String(data, 0, len, StandardCharsets.UTF_8));
                    props.put(SERVER_REFERENCE, sr);
                    break;
                case REASON_STRING_ID:
                    len = (_buf.get(rpos++) & 0xFF) << 8 | _buf.get(rpos++) & 0xFF;
                    data = parseContext.getParseData();
                    if (data.length < len) {
                        data = new byte[len];
                        parseContext.setParseData(data);
                    }
                    rpos += _buf.get(rpos, data, 0, len);
                    ReasonString rs = new ReasonString();
                    rs.value(new String(data, 0, len, StandardCharsets.UTF_8));
                    props.put(REASON_STRING, rs);
                    break;
                case RECEIVE_MAXINUM_ID:
                    ReceiveMaximum rm = new ReceiveMaximum();
                    rm.value((_buf.get(rpos++) & 0xFF) << 8 | _buf.get(rpos++) & 0xFF);
                    props.put(RECEIVE_MAXINUM, rm);
                    break;
                case TOPIC_ALIAS_MAXIMUM_ID:
                    TopicAliasMaximum tam = new TopicAliasMaximum();
                    tam.value((_buf.get(rpos++) & 0xFF) << 8 | _buf.get(rpos++) & 0xFF);
                    props.put(TOPIC_ALIAS_MAXIMUM, tam);
                    break;
                case TOPIC_ALIAS_ID:
                    TopicAlias ta = new TopicAlias();
                    ta.value((_buf.get(rpos++) & 0xFF) << 8 | _buf.get(rpos++) & 0xFF);
                    props.put(TOPIC_ALIAS, ta);
                    break;
                case MAXIMUM_QOS_ID:
                    MaximumQoS mm = new MaximumQoS();
                    mm.value(_buf.get(rpos++));
                    props.put(MAXIMUM_QOS, mm);
                    break;
                case RETAIN_AVAILABLE_ID:
                    RetainAvailable ra = new RetainAvailable();
                    ra.value(_buf.get(rpos++));
                    props.put(RETAIN_AVAILABLE, ra);
                    break;
                case USER_PROPERTY_ID:
                    UserProperty up = (UserProperty)props.get(USER_PROPERTY);
                    if (up == null) {
                        up = new UserProperty();
                        props.put(USER_PROPERTY, up);
                    }
                    List<StringPair> pairs = up.value();
                    if (pairs == null) {
                        pairs = new ArrayList<>();
                    }
                    StringPair sp = new StringPair();
                    len = (_buf.get(rpos++) & 0xFF) << 8 | _buf.get(rpos++) & 0xFF;
                    data = parseContext.getParseData();
                    if (data.length < len) {
                        data = new byte[len];
                        parseContext.setParseData(data);
                    }
                    rpos += _buf.get(rpos, data, 0, len);
                    sp.setName(new String(data, 0, len, StandardCharsets.UTF_8));
                    len = (_buf.get(rpos++) & 0xFF) << 8 | _buf.get(rpos++) & 0xFF;
                    data = parseContext.getParseData();
                    if (data.length < len) {
                        data = new byte[len];
                        parseContext.setParseData(data);
                    }
                    rpos += _buf.get(rpos, data, 0, len);
                    sp.setValue(new String(data, 0, len, StandardCharsets.UTF_8));
                    pairs.add(sp);
                    up.value(pairs);
                    props.put(USER_PROPERTY, up);
                    break;
                case MAXIMUM_PACKET_SIZE_ID:
                    MaximumPacketSize mps = new MaximumPacketSize();
                    mps.value((_buf.get(rpos++) & 0xFF) << 24 |
                            (_buf.get(rpos++) & 0xFF) << 16 |
                            (_buf.get(rpos++) & 0xFF) << 8 |
                            _buf.get(rpos++) & 0xFF);
                    props.put(MAXIMUM_PACKET_SIZE, mps);
                    break;
                case WILDCARD_SUBSCRIPTION_AVAILABLE_ID:
                    WildcardSubscriptionAvailable wsa = new WildcardSubscriptionAvailable();
                    wsa.value(_buf.get(rpos++));
                    props.put(WILDCARD_SUBSCRIPTION_AVAILABLE, wsa);
                    break;
                case SUBSCRIPTION_INDENTIFIER_AVAILABLE_ID:
                    SubscriptionIdentifierAvailable sia = new SubscriptionIdentifierAvailable();
                    sia.value(_buf.get(rpos++));
                    props.put(SUBSCRIPTION_INDENTIFIER_AVAILABLE, sia);
                    break;
                case SHARED_SUBSCRIPTION_AVAILABLE_ID:
                    SharedSubscriptionAvailable ssa = new SharedSubscriptionAvailable();
                    ssa.value(_buf.get(rpos++));
                    props.put(SHARED_SUBSCRIPTION_AVAILABLE, ssa);
                    break;
                default:
                    break;
            }
        }
        parseContext.setRpos(rpos);
        return props;
    }
}
