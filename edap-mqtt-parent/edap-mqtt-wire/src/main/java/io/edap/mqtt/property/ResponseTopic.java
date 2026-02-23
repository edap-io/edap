package io.edap.mqtt.property;

/**
 * @since mqtt-v5.0
 */
public class ResponseTopic extends StringProperty {

    static final String NAME = "Response Topic";

    public ResponseTopic() {}

    public ResponseTopic(String val) {
        value(val);
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public int identifier() {
        return RESPONSE_TOPIC_ID;
    }
}
