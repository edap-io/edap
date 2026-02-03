package io.edap.mqtt.property;

/**
 * @since mqtt-v5.0
 */
public class SessionExpiryInterval extends IntegerProperty {

    static final String NAME = "Session Expiry Interval";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public int identifier() {
        return SESSION_EXPIRY_INTERVAL_ID;
    }
}
