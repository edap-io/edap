package io.edap.mqtt.property;

/**
 * @since mqtt-v5.0
 */
public class ResponseInformation extends StringProperty {

    static final String NAME = "Response Information";

    public ResponseInformation() {}

    public ResponseInformation(String val) {
        value(val);
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public int identifier() {
        return RESPONSE_INFORMATION_ID;
    }
}
