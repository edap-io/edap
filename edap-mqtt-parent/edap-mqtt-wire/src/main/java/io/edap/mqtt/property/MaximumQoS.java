package io.edap.mqtt.property;

/**
 * @since mqtt-v5.0
 */
public class MaximumQoS extends ByteProperty {

    static final String NAME = "Maximum QoS";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public int identifier() {
        return MAXIMUM_QOS_ID;
    }
}
