package io.edap.mqtt.property;

/**
 * @since mqtt-v5.0
 */
public class TopicAlias extends TwoByteIntegerProperty {

    static final String NAME = "Topic Alias";

    public TopicAlias() {}

    public TopicAlias(int val) {
        value(val);
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public int identifier() {
        return TOPIC_ALIAS_ID;
    }
}
