package io.edap.mqtt.property;

/**
 * @since mqtt-v5.0
 */
public class SharedSubscriptionAvailable extends ByteProperty {

    static final String NAME = "Shared Subscription Available";

    public SharedSubscriptionAvailable() {}

    public SharedSubscriptionAvailable(byte val) {
        value(val);
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public int identifier() {
        return SHARED_SUBSCRIPTION_AVAILABLE_ID;
    }
}
