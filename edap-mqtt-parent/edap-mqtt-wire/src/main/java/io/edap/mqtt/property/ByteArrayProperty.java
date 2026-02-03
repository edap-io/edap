package io.edap.mqtt.property;

import io.edap.mqtt.ByteArrayToLongException;
import io.edap.mqtt.MqttWriter;
import io.edap.mqtt.PacketProperty;

import static io.edap.mqtt.MqttConstant.TWO_BYTE_INT_MAX_VALUE;

/**
 * @since mqtt-v5.0
 */
public abstract class ByteArrayProperty implements PacketProperty<byte[]> {

    private byte[] value;

    @Override
    public byte[] value() {
        return value;
    }

    @Override
    public void value(byte[] value) {
        this.value = value;
    }

    @Override
    public void writeTo(MqttWriter writer) {
        if (value == null) {
            return;
        }
        int len = value.length;
        if (len > TWO_BYTE_INT_MAX_VALUE) {
            throw new ByteArrayToLongException("byte array data too lang!");
        }
        writer.writeBytes((byte)(len >> 8), (byte)(len & 0xFF));
        writer.write(value);
    }

}
