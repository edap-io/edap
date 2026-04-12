package io.edap.mqtt;

import io.edap.mqtt.decoder.*;

public class MqttPacketDecoderFactory {

    public static final AuthDecoder        AUTH_DECODER        = new AuthDecoder();
    public static final ConnAckDecoder     CONN_ACK_DECODER    = new ConnAckDecoder();
    public static final ConnectDecoder     CONNECT_DECODER     = new ConnectDecoder();
    public static final DisconnectDecoder  DISCONNECT_DECODER  = new DisconnectDecoder();
    public static final PingReqDecoder     PING_REQ_DECODER    = new PingReqDecoder();
    public static final PingRespDecoder    PING_RESP_DECODER   = new PingRespDecoder();
    public static final PubAckDecoder      PUB_ACK_DECODER     = new PubAckDecoder();
    public static final PubCompDecoder     PUB_COMP_DECODER    = new PubCompDecoder();
    public static final PublishDecoder     PUBLISH_DECODER     = new PublishDecoder();
    public static final PubRecDecoder      PUB_REC_DECODER     = new PubRecDecoder();
    public static final PubRelDecoder      PUB_REL_DECODER     = new PubRelDecoder();
    public static final SubAckDecoder      SUB_ACK_DECODER     = new SubAckDecoder();
    public static final SubscribeDecoder   SUBSCRIBE_DECODER   = new SubscribeDecoder();
    public static final UnsubAckDecoder    UNSUB_ACK_DECODER   = new UnsubAckDecoder();
    public static final UnsubscribeDecoder UNSUBSCRIBE_DECODER = new UnsubscribeDecoder();
    public static final UnsupportDecoder   UNSUPPORT_DECODER   = new UnsupportDecoder();
}
