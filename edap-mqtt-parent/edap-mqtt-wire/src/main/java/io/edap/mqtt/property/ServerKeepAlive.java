package io.edap.mqtt.property;

/**
 * @since mqtt-v5.0
 */
public class ServerKeepAlive extends TwoByteIntegerProperty {

    static final String NAME = "Server Keep Alive";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public int identifier() {
        return SERVER_KEEP_ALIVE_ID;
    }
}
