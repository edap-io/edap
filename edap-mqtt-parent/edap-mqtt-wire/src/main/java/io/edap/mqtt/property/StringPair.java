package io.edap.mqtt.property;

import io.edap.mqtt.MqttWriter;

/**
 * @since mqtt-v5.0
 */
public class StringPair {
    private String name;
    private String value;

    public StringPair() {

    }

    public StringPair(String name, String value) {
        this.name = name;
        this.value = value;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getValue() {
        return value;
    }

    public void setValue(String value) {
        this.value = value;
    }

    public void writeTo(MqttWriter writer) {
        writer.writeString(name);
        writer.writeString(value);
    }
}
