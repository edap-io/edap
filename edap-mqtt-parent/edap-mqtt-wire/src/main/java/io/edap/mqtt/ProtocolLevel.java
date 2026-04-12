package io.edap.mqtt;

public enum ProtocolLevel {
    UNKNOWN      (0),
    VERSION_3_1  (3),
    VERSION_3_1_1(4),
    VERSION_5    (5);

    static ProtocolLevel[] LEVELS = new ProtocolLevel[] {
            UNKNOWN,
            UNKNOWN,
            UNKNOWN,
            VERSION_3_1,
            VERSION_3_1_1,
            VERSION_5
    };

    private int value;

    ProtocolLevel(int value) {
        this.value = value;
    }

    public int getValue() {
        return value;
    }

    public static ProtocolLevel fromValue(int value) {
        if (value >= 0 && value < 6) {
            return LEVELS[value];
        }

        return UNKNOWN;
    }
}
