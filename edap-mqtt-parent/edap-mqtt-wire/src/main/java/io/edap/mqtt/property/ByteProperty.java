package io.edap.mqtt.property;

import io.edap.mqtt.MqttWriter;
import io.edap.mqtt.PacketProperty;

/**
 * @since mqtt-v5.0
 */
public abstract class ByteProperty implements PacketProperty<Byte> {

    private byte value;

    @Override
    public Byte value() {
        return value;
    }

    @Override
    public void value(Byte value) {
        this.value = value;
    }

    @Override
    public void writeTo(MqttWriter writer) {
        writer.writeByte(value);
    }
}
