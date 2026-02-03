package io.edap.mqtt.property;

import io.edap.mqtt.MqttWriter;
import io.edap.mqtt.PacketProperty;

import static io.edap.mqtt.MqttConstant.TWO_BYTE_INT_MAX_VALUE;

/**
 * @since mqtt-v5.0
 */
public abstract class TwoByteIntegerProperty implements PacketProperty<Integer> {

    private int value;

    @Override
    public Integer value() {
        return value;
    }

    @Override
    public void value(Integer value) {
        this.value = value;
    }

    @Override
    public void writeTo(MqttWriter writer) {
        int val = value;
        writer.writeBytes((byte)(val >> 8), (byte)(val & 0xFF));
    }

}
