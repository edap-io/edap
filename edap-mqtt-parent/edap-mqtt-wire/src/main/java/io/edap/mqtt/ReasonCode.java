package io.edap.mqtt;

public enum ReasonCode {

    SUCCESS                     (  0, "The Connection is accepted."),
    UNACCEPTABLE_VERSION        (  1, "The Server does not support the level of the MQTT protocol requested by the Client"),
    IDENTIFIER_REJECTED         (  2, "The Client identifier is correct UTF-8 but not allowed by the Server"),
    SERVER_UNAVAILABLE_V3       (  3, "The Network Connection has been made but the MQTT service is unavailable"),
    BAD_USERNAME_OR_PASSWORD_V3 (  4, "The data in the user name or password is malformed"),
    NOT_AUTHORIZED_V3           (  5, "The Client is not authorized to connect"),
    NO_MATCHING_SUBSCRIBERS     ( 16, ""),
    NO_SUBSCRIPTION_EXISTED     ( 17, ""),
    CONTINUE_AUTHENTICATION     ( 24, ""),
    RE_AUTHENTICATION           ( 25, ""),
    UNSPECIFIED_ERROR           (128, "The Server does not wish to reveal the reason for the failure, or none of the other Reason Codes apply."),
    MALFORMED_PACKET            (129, "Data within the CONNECT packet could not be correctly parsed."),
    PROTOCOL_ERROR              (130, "Data in the CONNECT packet does not conform to this specification."),
    IMPLEMENTATION_ERROR        (131, "The CONNECT is valid but is not accepted by this Server."),
    UNSUPPORTED_VERSION         (132, "The Server does not support the version of the MQTT protocol requested by the Client."),
    CLIENT_INDENTIFIER_INVALID  (133, "The Client Identifier is a valid string but is not allowed by the Server."),
    BAD_USERNAME_OR_PASSWORD    (134, "The Server does not accept the User Name or Password specified by the Client"),
    NOT_AUTHORIZED              (135, "The Client is not authorized to connect."),
    SERVER_UNAVAILABLE          (136, "The MQTT Server is not available."),
    SERVER_BUSY                 (137, "The Server is busy. Try again later."),
    BANNED                      (138, "This Client has been banned by administrative action. Contact the server administrator."),
    SERVER_SHUTTING_DOWN        (139, ""),
    BAD_AUTHENTICATION_METHOD   (140, "The authentication method is not supported or does not match the authentication method currently in use."),
    KEEP_ALIVE_TIMEOUT          (141, ""),
    SESSION_TOKEN_OVER          (142, ""),
    TOPIC_FILTER_INVALID        (143, ""),
    TOPIC_NAME_INVALID          (144, "The Will Topic Name is not malformed, but is not accepted by this Server."),
    PACKET_IDENTIFIER_IN_USE    (145, ""),
    PACKET_IDENTIFIER_NOT_FOUND (146, ""),
    RECEIVE_MAXIMUM_EXCEEDED    (147, ""),
    TOPIC_ALIAS_INVALID         (148, ""),
    PACKET_TOO_LARGE            (149, "The CONNECT packet exceeded the maximum permissible size."),
    MESSAGE_RATE_TOO_HIGH       (150, ""),
    QUOTA_EXCEEDED              (151, "An implementation or administrative imposed limit has been exceeded."),
    ADMINISTRATIVE_ACTION       (152, ""),
    PAYLOAD_FORMAT_INVALID      (153, "The Will Payload does not match the specified Payload Format Indicator."),
    RETAIN_NOT_SUPPORTED        (154, "The Server does not support retained messages, and Will Retain was set to 1."),
    QOS_NOT_SUPPORTED           (155, "The Server does not support the QoS set in Will QoS."),
    USER_ANOTHER                (156, "The Client should temporarily use another server."),
    SERVER_MOVED                (157, "The Client should permanently use another server."),
    SHARED_SUB_NOT_SUPPORTED    (158, ""),
    CONNECTION_RATE_EXCEEDED    (159, "The connection rate limit has been exceeded."),
    MAXIMUM_CONNECT_TIME        (160, ""),
    SUB_IDENTIFIER_NOT_SUPPORTED(161, ""),
    WILDCARD_SUB_NOT_SUPPORTED  (162, "");

    private int    code;
    private String description;

    ReasonCode(int code, String desc) {
        this.code        = code;
        this.description = desc;
    }

    public int getCode() {
        return code;
    }

    public String getDescription() {
        return description;
    }

    static ReasonCode[] REASON_CODES;
    static {
        ReasonCode[] codes = ReasonCode.class.getEnumConstants();
        int maxCode = 0;
        for (ReasonCode rc : codes) {
            if (rc.code > maxCode) {
                maxCode = rc.code;
            }
        }
        REASON_CODES = new ReasonCode[maxCode + 1];
        for (int i=0;i<maxCode;i++) {
            REASON_CODES[i] = null;
        }
        for (ReasonCode rc : codes) {
            REASON_CODES[rc.code] = rc;
        }
    }

    public static ReasonCode fromCode(int code) {
        if (code >= 0 && code < REASON_CODES.length) {
            ReasonCode rc = REASON_CODES[code];
            if (rc == null) {
                throw new EnumConstantNotPresentException(ReasonCode.class, "code is " + code);
            }
            return REASON_CODES[code];
        }

        throw new EnumConstantNotPresentException(ReasonCode.class, "code is " + code);
    }
}
