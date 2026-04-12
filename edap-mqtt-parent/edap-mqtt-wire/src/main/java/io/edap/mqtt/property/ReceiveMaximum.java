package io.edap.mqtt.property;

/**
 * @since mqtt-v5.0
 */
public class ReceiveMaximum extends TwoByteIntegerProperty {

    static final String NAME = "Receive Maximum";

    public ReceiveMaximum() {

    }

    public ReceiveMaximum(int val) {
        value(val);
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public int identifier() {
        return RECEIVE_MAXINUM_ID;
    }
}
