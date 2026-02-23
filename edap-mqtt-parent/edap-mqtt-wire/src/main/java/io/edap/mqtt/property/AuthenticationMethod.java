package io.edap.mqtt.property;

/**
 * @since mqtt-v5.0
 */
public class AuthenticationMethod extends StringProperty {

    static final String NAME = "Authentication Method";

    public AuthenticationMethod() {

    }

    public AuthenticationMethod(String val) {
        value(val);
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public int identifier() {
        return AUTHENTICATION_METHOD_ID;
    }
}
