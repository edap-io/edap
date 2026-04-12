package io.edap.mqtt.property;

/**
 * @since mqtt-v5.0
 */
public class RequestProblemInformation extends ByteProperty {

    static final String NAME = "Request Problem Information";

    public RequestProblemInformation() {

    }

    public RequestProblemInformation(byte val) {
        value(val);
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public int identifier() {
        return REQUEST_PROBLEM_INFORMATION_ID;
    }
}
