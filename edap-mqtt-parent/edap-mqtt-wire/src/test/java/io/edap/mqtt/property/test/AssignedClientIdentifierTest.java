package io.edap.mqtt.property.test;

import io.edap.mqtt.PropertyType;
import io.edap.mqtt.property.AssignedClientIdentifier;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static io.edap.mqtt.packet.test.ConnectTest.randomStr;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class AssignedClientIdentifierTest {

    @Test
    public void testName() {
        AssignedClientIdentifier aci = new AssignedClientIdentifier();
        assertEquals(aci.name(), "Assigned Client Identifier");
    }

    @Test
    public void testIdentifier() {
        AssignedClientIdentifier aci = new AssignedClientIdentifier();
        assertEquals(aci.identifier(), PropertyType.ASSIGNED_CLIENT_IDENTIFIER.getType());
    }

    @Test
    public void testValue() {
        AssignedClientIdentifier aci = new AssignedClientIdentifier();
        String value = randomStr(new Random().nextInt(40));
        aci.value(value);
        assertEquals(value, aci.value());
    }
}
