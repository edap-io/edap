package io.edap.mqtt.property.test;

import io.edap.mqtt.MqttWriter;
import io.edap.mqtt.PropertyType;
import io.edap.mqtt.property.StringPair;
import io.edap.mqtt.property.UserProperty;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static io.edap.mqtt.PacketProperty.USER_PROPERTY_ID;
import static org.junit.jupiter.api.Assertions.*;

public class UserPropertyTest {

    @Test
    public void testName() {
        UserProperty up = new UserProperty();
        assertEquals(up.name(), "User Property");
    }

    @Test
    public void testIdentifier() {
        UserProperty up = new UserProperty();
        assertEquals(up.identifier(), PropertyType.USER_PROPERTY.getType());
    }

    @Test
    public void testValue() {
        UserProperty up = new UserProperty();
        assertNull(up.value());
        List<StringPair> pairs = new ArrayList<>();
        up.value(pairs);
        assertEquals(up.value().size(), 0);
        StringPair pair = new StringPair();
        pair.setName("name");
        pair.setValue("root");
        pairs.add(pair);
        assertEquals(up.value().get(0).getName(), "name");
        assertEquals(up.value().get(0).getValue(), "root");
    }

    @Test
    public void testWriteTo() {
        UserProperty up = new UserProperty();
        assertNull(up.value());
        List<StringPair> pairs = new ArrayList<>();
        up.value(pairs);
        assertEquals(up.value().size(), 0);
        StringPair pair = new StringPair();
        pair.setName("name");
        pair.setValue("root1");
        pairs.add(pair);
        assertEquals(up.value().get(0).getName(), "name");
        assertEquals(up.value().get(0).getValue(), "root1");

        MqttWriter writer = new MqttWriter();
        up.writeTo(writer);
        byte[] data = new byte[writer.getLength()];
        System.arraycopy(writer.getData(), writer.getStart(), data, 0, writer.getLength());
        byte[] expected = new byte[]{0, 4, 'n', 'a', 'm', 'e', 0, 5, 'r', 'o','o','t', '1'};
        assertArrayEquals(data, expected);

        StringPair pair2 = new StringPair();
        pair2.setName("n");
        pair2.setValue("r1");
        pairs.add(pair2);
        writer = new MqttWriter();
        up.writeTo(writer);
        data = new byte[writer.getLength()];
        System.arraycopy(writer.getData(), writer.getStart(), data, 0, writer.getLength());
        expected = new byte[]{0, 4, 'n', 'a', 'm', 'e', 0, 5, 'r', 'o','o','t', '1',USER_PROPERTY_ID, 0,1,'n',0,2,'r','1'};
        assertArrayEquals(data, expected);

        pairs.clear();
        writer = new MqttWriter();
        up.writeTo(writer);
        data = new byte[writer.getLength()];
        System.arraycopy(writer.getData(), writer.getStart(), data, 0, writer.getLength());
        expected = new byte[0];
        assertArrayEquals(data, expected);
    }
}
