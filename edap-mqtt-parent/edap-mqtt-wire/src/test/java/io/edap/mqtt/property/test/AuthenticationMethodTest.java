package io.edap.mqtt.property.test;

import io.edap.mqtt.PropertyType;
import io.edap.mqtt.property.AuthenticationMethod;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class AuthenticationMethodTest {

    @Test
    public void testName() {
        AuthenticationMethod am = new AuthenticationMethod();
        assertEquals(am.name(), "Authentication Method");
    }

    @Test
    public void testIdentifier() {
        AuthenticationMethod am = new AuthenticationMethod();
        assertEquals(am.identifier(), PropertyType.AUTHENTICATION_METHOD.getType());
    }
}
