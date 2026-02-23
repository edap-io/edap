package io.edap.mqtt.property;

/**
 * @since mqtt-v5.0
 */
public class RequestResponseInformation extends ByteProperty {

    static final String NAME = "Request Response Information";

    public RequestResponseInformation() {

    }

    public RequestResponseInformation(byte val) {
        value(val);
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public int identifier() {
        return REQUEST_RESPONSE_INFORMATION_ID;
    }
}
