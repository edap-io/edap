package io.edap.mqtt.property;

/**
 * @since mqtt-v5.0
 */
public class MaximumPacketSize extends IntegerProperty {

    static final String NAME = "Maximum Packet Size";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public int identifier() {
        return MAXIMUM_PACKET_SIZE_ID;
    }
}
