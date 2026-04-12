package io.edap.mqtt.property;

import io.edap.mqtt.MqttWriter;
import io.edap.mqtt.PacketProperty;
import io.edap.mqtt.PropertyType;
import io.edap.util.CollectionUtils;

import java.util.List;

/**
 * @since mqtt-v5.0
 */
public class UserProperty implements PacketProperty<List<StringPair>> {

    static final String NAME = "User Property";

    private List<StringPair> value;

    @Override
    public List<StringPair> value() {
        return value;
    }

    @Override
    public void value(List<StringPair> value) {
        this.value = value;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public int identifier() {
        return USER_PROPERTY_ID;
    }

    @Override
    public void writeTo(MqttWriter writer) {
        List<StringPair> _val = value;
        if (CollectionUtils.isEmpty(_val)) {
            return;
        }
        int size = _val.size();
        _val.get(0).writeTo(writer);
        for (int i=1;i<size;i++) {
            writer.writeByte((byte)USER_PROPERTY_ID);
            _val.get(i).writeTo(writer);
        }
    }
}
