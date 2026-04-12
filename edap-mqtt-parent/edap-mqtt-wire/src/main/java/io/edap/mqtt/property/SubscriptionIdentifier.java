package io.edap.mqtt.property;

import io.edap.mqtt.IntegerToLongException;
import io.edap.mqtt.MqttWriter;
import io.edap.mqtt.PacketProperty;

/**
 * @since mqtt-v5.0
 */
public class SubscriptionIdentifier implements PacketProperty<Integer> {

    static final String NAME = "Subscription Identifier";

    private int value;

    public SubscriptionIdentifier() {}

    public SubscriptionIdentifier(int val) {
        value(val);
    }

    @Override
    public Integer value() {
        return value;
    }

    @Override
    public void value(Integer value) {
        this.value = value;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public int identifier() {
        return SUBSCRIPTION_INDENTIFIER_ID;
    }

    @Override
    public void writeTo(MqttWriter writer) {
        int val = value;
        if ((val & ~0x7F) == 0) {
            writer.writeByte((byte)(val & 0x7F));
        } else {
            writer.writeByte((byte) ((val & 0x7F) | 0x80));
            val >>>= 7;
            if ((val & ~0x7F) == 0) {
                writer.writeByte((byte) val);
            } else {
                writer.writeByte((byte) ((val & 0x7F) | 0x80));
                val >>>= 7;
                if ((val & ~0x7F) == 0) {
                    writer.writeByte((byte) val);
                } else {
                    writer.writeByte((byte) ((val & 0x7F) | 0x80));
                    val >>>= 7;
                    if ((val & ~0x7F) == 0) {
                        writer.writeByte((byte) val);
                    } else {
                        throw new IntegerToLongException("Integer " + value + " too big");
                    }
                }
            }
        }
    }
}
