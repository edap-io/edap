package io.edap.mqtt.property.test;

import io.edap.mqtt.PropertyType;
import io.edap.mqtt.property.TopicAlias;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

public class TopicAliasTest {

    @Test
    public void testName() {
        TopicAlias ta = new TopicAlias();
        assertEquals(ta.name(), "Topic Alias");
    }

    @Test
    public void testIdentifier() {
        TopicAlias ta = new TopicAlias();
        assertEquals(ta.identifier(), PropertyType.TOPIC_ALIAS.getType());
    }

    @Test
    public void testValue() {
        TopicAlias ta = new TopicAlias();
        assertNotNull(ta.value());
        assertEquals(ta.value().shortValue(), 0);

        Integer value = new Random().nextInt(Short.MAX_VALUE);
        ta.value(value);
        assertEquals(ta.value().shortValue(), value.shortValue());
    }
}
