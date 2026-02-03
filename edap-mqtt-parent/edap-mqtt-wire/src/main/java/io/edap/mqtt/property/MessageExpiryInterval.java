package io.edap.mqtt.property;

/**
 * @since mqtt-v5.0
 */
public class MessageExpiryInterval extends IntegerProperty {

    static final String NAME = "Message Expiry Interval";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public int identifier() {
        return MESSAGE_EXPIRY_INTERVAL_ID;
    }
}
