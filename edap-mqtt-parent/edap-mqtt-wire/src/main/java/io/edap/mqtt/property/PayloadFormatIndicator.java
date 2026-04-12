package io.edap.mqtt.property;

/**
 * @since mqtt-v5.0
 */
public class PayloadFormatIndicator extends ByteProperty {

    static final String NAME = "Payload Format Indicator";

    public PayloadFormatIndicator() {}

    public PayloadFormatIndicator(byte val) {
        value(val);
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public int identifier() {
        return PAYLOAD_FORMAT_INDICATOR_ID;
    }
}
