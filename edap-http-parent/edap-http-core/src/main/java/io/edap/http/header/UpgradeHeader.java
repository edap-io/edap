package io.edap.http.header;

public class UpgradeHeader extends Header {

    public static UpgradeHeader UPGRADE_WEBSOCKET = new UpgradeHeader("Upgrade", "websocket");

    /** h2c 明文升级（RFC 9113 §3.2） */
    public static UpgradeHeader UPGRADE_H2C = new UpgradeHeader("Upgrade", "h2c");

    private UpgradeHeader(String name, String value) {
        super(name, value);
    }
}
