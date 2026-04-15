package io.edap.http.header;

public class UpgradeHeader extends Header {

    public static UpgradeHeader UPGRADE_WEBSOCKET = new UpgradeHeader("Upgrade", "websocket");

    private UpgradeHeader(String name, String value) {
        super(name, value);
    }
}
