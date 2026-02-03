package io.edap.mqtt.property;

/**
 * @since mqtt-v5.0
 */
public class ContentType extends StringProperty {

    static final String NAME = "Content Type";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public int identifier() {
        return CONTENT_TYPE_ID;
    }
}
