package io.edap.mqtt.test.wire.test;

import io.edap.mqtt.wire.Auth;
import io.edap.mqtt.wire.ControlPacketType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class AuthTest {

    @Test
    public void testConstuct() {
        Auth auth = new Auth(50);
        assertEquals(auth.getType(), ControlPacketType.AUTH);
    }
}
