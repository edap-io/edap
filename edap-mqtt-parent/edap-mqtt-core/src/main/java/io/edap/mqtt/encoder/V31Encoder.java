package io.edap.mqtt.encoder;

import io.edap.mqtt.MqttEncoder;
import io.edap.mqtt.MqttWriter;
import io.edap.mqtt.ProtocolLevel;
import io.edap.mqtt.packet.Connect;

public class V31Encoder implements MqttEncoder {

    @Override
    public ProtocolLevel getProtocelLevel() {
        return ProtocolLevel.VERSION_3_1;
    }
}
