package io.edap.mqtt.property;

/**
 * @since mqtt-v5.0
 */
public class CorrelationData extends ByteArrayProperty {

    static final String NAME = "Correlation Data";

    private byte[] value;

    @Override
    public byte[] value() {
        return value;
    }

    @Override
    public void value(byte[] value) {
        this.value = value;
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
