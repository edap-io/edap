package io.edap.mqtt.property;

/**
 * @since mqtt-v5.0
 */
public class TopicAliasMaximum extends TwoByteIntegerProperty {

    static final String NAME = "Topic Alias Maximum";

    public TopicAliasMaximum() {

    }

    public TopicAliasMaximum(int val) {
        value(val);
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public int identifier() {
        return TOPIC_ALIAS_MAXIMUM_ID;
    }
}
