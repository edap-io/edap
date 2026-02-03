package io.edap.mqtt.property;

/**
 * @since mqtt-v5.0
 */
public class SubscriptionIdentifierAvailable extends ByteProperty {

    static final String NAME = "Subscription Identifier Available";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public int identifier() {
        return SUBSCRIPTION_INDENTIFIER_AVAILABLE_ID;
    }
}
