package io.edap.mqtt.property;

/**
 * @since mqtt-v5.0
 */
public class WillDelayInterval extends IntegerProperty {

    static final String NAME = "Will Delay Interval";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public int identifier() {
        return WILL_DELAY_INTERVAL_ID;
    }
}
