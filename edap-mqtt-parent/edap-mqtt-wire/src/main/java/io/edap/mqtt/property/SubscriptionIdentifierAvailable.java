package io.edap.mqtt.property;

/**
 * @since mqtt-v5.0
 */
public class SubscriptionIdentifierAvailable extends ByteProperty {

    static final String NAME = "Subscription Identifier Available";

    public SubscriptionIdentifierAvailable() {}

    public SubscriptionIdentifierAvailable(byte val) {
        value(val);
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public int identifier() {
        return SUBSCRIPTION_INDENTIFIER_AVAILABLE_ID;
    }
}
