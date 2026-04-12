package io.edap.mqtt.property;

/**
 * @since mqtt-v5.0
 */
public class AssignedClientIdentifier extends StringProperty {

    static final String NAME = "Assigned Client Identifier";

    public AssignedClientIdentifier() {}

    public AssignedClientIdentifier(String val) {
        value(val);
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public int identifier() {
        return ASSIGNED_CLIENT_IDENTIFIER_ID;
    }
}
