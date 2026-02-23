package io.edap.mqtt.property;

/**
 * @since mqtt-v5.0
 */
public class CorrelationData extends ByteArrayProperty {

    static final String NAME = "Correlation Data";

    public CorrelationData() {}

    public CorrelationData(byte[] val) {
        value(val);
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public int identifier() {
        return CORRELATION_DATA_ID;
    }
}
