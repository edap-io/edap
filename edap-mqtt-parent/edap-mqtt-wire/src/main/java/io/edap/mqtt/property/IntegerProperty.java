package io.edap.mqtt.property;

import io.edap.mqtt.MqttWriter;
import io.edap.mqtt.PacketProperty;

/**
 * @since mqtt-v5.0
 */
public abstract class IntegerProperty implements PacketProperty<Integer> {

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
        writer.writeBytes((byte)(val >> 24), (byte)(val >> 16), (byte)(val >> 8), (byte)(val & 0xFF));
    }
}
