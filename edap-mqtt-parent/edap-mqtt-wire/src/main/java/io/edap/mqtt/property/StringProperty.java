package io.edap.mqtt.property;

import io.edap.mqtt.MqttWriter;
import io.edap.mqtt.PacketProperty;
import io.edap.mqtt.StringToLongException;
import io.edap.util.StringUtil;
import io.edap.util.UnsafeUtil;

import static io.edap.mqtt.MqttConstant.TWO_BYTE_INT_MAX_VALUE;
import static io.edap.util.StringUtil.*;

/**
 * @since mqtt-v5.0
 */
public abstract class StringProperty implements PacketProperty<String> {

    private String value;

    @Override
    public String value() {
        return value;
    }

    @Override
    public void value(String value) {
        this.value = value;
    }

    @Override
    public void writeTo(MqttWriter writer) {
        if (StringUtil.isEmpty(value)) {
            return;
        }
        writer.writeString(value);
    }

}
