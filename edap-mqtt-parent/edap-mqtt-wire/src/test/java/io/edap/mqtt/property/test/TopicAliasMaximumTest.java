package io.edap.mqtt.property.test;

import io.edap.mqtt.PropertyType;
import io.edap.mqtt.property.TopicAliasMaximum;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

public class TopicAliasMaximumTest {

    @Test
    public void testName() {
        TopicAliasMaximum tam = new TopicAliasMaximum();
        assertEquals(tam.name(), "Topic Alias Maximum");
    }

    @Test
    public void testIdentifier() {
        TopicAliasMaximum tam = new TopicAliasMaximum();
        assertEquals(tam.identifier(), PropertyType.TOPIC_ALIAS_MAXIMUM.getType());
    }

    @Test
    public void testValue() {
        TopicAliasMaximum tam = new TopicAliasMaximum();
        assertNotNull(tam.value());
        assertEquals(tam.value().shortValue(), 0);
        Integer value = new Random().nextInt(Short.MAX_VALUE);
        tam.value(value);
        assertEquals(tam.value().shortValue(), value.shortValue());
    }
}
