package io.edap.mqtt;

/**
 * @since mqtt-v5.0
 */
public interface PacketProperty<T> {

    int PAYLOAD_FORMAT_INDICATOR_ID           = 1;
    int MESSAGE_EXPIRY_INTERVAL_ID            = 2;
    int CONTENT_TYPE_ID                       = 3;
    int RESPONSE_TOPIC_ID                     = 8;
    int CORRELATION_DATA_ID                   = 9;
    int SUBSCRIPTION_INDENTIFIER_ID           = 11;
    int SESSION_EXPIRY_INTERVAL_ID            = 17;
    int ASSIGNED_CLIENT_IDENTIFIER_ID         = 18;
    int SERVER_KEEP_ALIVE_ID                  = 19;
    int AUTHENTICATION_METHOD_ID              = 21;
    int AUTHENTICATION_DATA_ID                = 22;
    int REQUEST_PROBLEM_INFORMATION_ID        = 23;
    int WILL_DELAY_INTERVAL_ID                = 24;
    int REQUEST_RESPONSE_INFORMATION_ID       = 25;
    int RESPONSE_INFORMATION_ID               = 26;
    int SERVER_REFERENCE_ID                   = 28;
    int REASON_STRING_ID                      = 31;
    int RECEIVE_MAXINUM_ID                    = 33;
    int TOPIC_ALIAS_MAXIMUM_ID                = 34;
    int TOPIC_ALIAS_ID                        = 35;
    int MAXIMUM_QOS_ID                        = 36;
    int RETAIN_AVAILABLE_ID                   = 37;
    int USER_PROPERTY_ID                      = 38;
    int MAXIMUM_PACKET_SIZE_ID                = 39;
    int WILDCARD_SUBSCRIPTION_AVAILABLE_ID    = 40;
    int SUBSCRIPTION_INDENTIFIER_AVAILABLE_ID = 41;
    int SHARED_SUBSCRIPTION_AVAILABLE_ID      = 42;

    T value();
    void value(T value);
    String name();
    int identifier();

    void writeTo(MqttWriter writer);
}
