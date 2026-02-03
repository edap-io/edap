package io.edap.mqtt.property;

/**
 * @since mqtt-v5.0
 */
public class WildcardSubscriptionAvailable extends ByteProperty {

    static final String NAME = "Wildcard Subscription Available";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public int identifier() {
        return WILDCARD_SUBSCRIPTION_AVAILABLE_ID;
    }
}
