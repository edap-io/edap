package io.edap.mqtt.property;

/**
 * @since mqtt-v5.0
 */
public class RequestResponseInformation extends ByteProperty {

    static final String NAME = "Request Response Information";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public int identifier() {
        return REQUEST_RESPONSE_INFORMATION_ID;
    }
}
