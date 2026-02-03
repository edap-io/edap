package io.edap.mqtt.property;

/**
 * @since mqtt-v5.0
 */
public class ReasonString extends StringProperty {

    static final String NAME = "Reason String";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public int identifier() {
        return REASON_STRING_ID;
    }
}
