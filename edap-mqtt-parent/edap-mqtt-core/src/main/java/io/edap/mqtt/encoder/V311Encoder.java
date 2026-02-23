package io.edap.mqtt.encoder;

import io.edap.mqtt.MqttEncoder;
import io.edap.mqtt.ProtocolLevel;

public class V311Encoder implements MqttEncoder {
    @Override
    public ProtocolLevel getProtocelLevel() {
        return ProtocolLevel.VERSION_3_1_1;
    }
}
