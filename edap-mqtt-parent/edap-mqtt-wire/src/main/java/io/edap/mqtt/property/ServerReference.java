package io.edap.mqtt.property;

/**
 * @since mqtt-v5.0
 */
public class ServerReference extends StringProperty {

    static final String NAME = "Server Reference";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public int identifier() {
        return SERVER_REFERENCE_ID;
    }
}
