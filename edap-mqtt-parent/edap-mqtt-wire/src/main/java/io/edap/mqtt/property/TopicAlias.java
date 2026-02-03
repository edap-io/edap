package io.edap.mqtt.property;

/**
 * @since mqtt-v5.0
 */
public class TopicAlias extends TwoByteIntegerProperty {

    static final String NAME = "Topic Alias";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public int identifier() {
        return TOPIC_ALIAS_ID;
    }
}
