package io.edap.mqtt.property;

/**
 * @since mqtt-v5.0
 */
public class AuthenticationData extends ByteArrayProperty {

    static final String NAME = "Authentication Data";

    public AuthenticationData() {

    }

    public AuthenticationData(byte[] val) {
        value(val);
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public int identifier() {
        return AUTHENTICATION_DATA_ID;
    }
}
