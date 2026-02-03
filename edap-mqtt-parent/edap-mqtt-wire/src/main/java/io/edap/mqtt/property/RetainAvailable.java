package io.edap.mqtt.property;

/**
 * @since mqtt-v5.0
 */
public class RetainAvailable extends ByteProperty {

    static final String NAME = "Retain Available";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public int identifier() {
        return RETAIN_AVAILABLE_ID;
    }
}
