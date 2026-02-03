package io.edap.mqtt;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class ReasonCodeTest {

    @Test
    public void testFromCode() {
        ReasonCode[] codes = ReasonCode.class.getEnumConstants();
        int maxCode = 0;
        Map<Integer, ReasonCode> codeValues = new HashMap<>();
        for (ReasonCode rc : codes) {
            if (rc.getCode() > maxCode) {
                maxCode = rc.getCode();
            }
            codeValues.put(rc.getCode(), rc);
        }

        for (int i=0;i<=maxCode;i++) {
            if (codeValues.containsKey(i)) {
                ReasonCode rc = ReasonCode.fromCode(i);
                assertEquals(rc, codeValues.get(i));
            } else {
                int v = i;
                EnumConstantNotPresentException thrown = assertThrows(EnumConstantNotPresentException.class,
                        () -> {
                            ReasonCode.fromCode(v);
                        });
                assertTrue(thrown.getMessage().contains("io.edap.mqtt.ReasonCode.code is " + v));
            }
        }

        ReasonCode success = ReasonCode.fromCode(0);
        success.getDescription().equals("The Connection is accepted.");

        int v = -1;
        EnumConstantNotPresentException thrown = assertThrows(EnumConstantNotPresentException.class,
                () -> {
                    ReasonCode.fromCode(v);
                });
        assertTrue(thrown.getMessage().contains("io.edap.mqtt.ReasonCode.code is " + v));

        int v2 = 200;
        thrown = assertThrows(EnumConstantNotPresentException.class,
                () -> {
                    ReasonCode.fromCode(v2);
                });
        assertTrue(thrown.getMessage().contains("io.edap.mqtt.ReasonCode.code is " + v2));
    }
}
