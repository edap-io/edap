package io.edap.mqtt.broker.test.utils;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static io.edap.mqtt.broker.utils.WildcardUtils.containsWildcard;
import static io.edap.mqtt.broker.utils.WildcardUtils.matchTopics;
import static org.junit.jupiter.api.Assertions.*;

public class WildcardUtilsTest {

    @Test
    public void testContainsWildcard() {
        String topicFilter = "";
        assertFalse(containsWildcard(topicFilter));

        topicFilter = "a";
        assertFalse(containsWildcard(topicFilter));

        topicFilter = "+a";
        assertFalse(containsWildcard(topicFilter));

        topicFilter = "a+";
        assertFalse(containsWildcard(topicFilter));

        topicFilter = "a/b";
        assertFalse(containsWildcard(topicFilter));

        topicFilter = "a+/b";
        assertFalse(containsWildcard(topicFilter));

        topicFilter = "a/+/c";
        assertTrue(containsWildcard(topicFilter));

        topicFilter = "+";
        assertTrue(containsWildcard(topicFilter));

        topicFilter = "#";
        assertTrue(containsWildcard(topicFilter));

        topicFilter = "a/b/#";
        assertTrue(containsWildcard(topicFilter));

        topicFilter = "a/b/c#";
        assertFalse(containsWildcard(topicFilter));
    }

    @Test
    public void testMatchTopics() {
        Set<String> allTopic = new HashSet<>();
        String wildcard = "";
        assertEquals(matchTopics(wildcard, allTopic).size(), 0);

        wildcard = "+";
        allTopic.add("a");
        allTopic.add("b");
        allTopic.add("c/d");
        List<String> topics = matchTopics(wildcard, allTopic);
        assertEquals(topics.size(), 2);
        assertTrue(topics.contains("a"));
        assertTrue(topics.contains("b"));

        allTopic.clear();
        wildcard = "a/+/W";
        allTopic.add("c/d/W");
        topics = matchTopics(wildcard, allTopic);
        assertEquals(topics.size(), 0);

        wildcard = "a/b/#";
        topics = matchTopics(wildcard, allTopic);
        assertEquals(topics.size(), 0);

        wildcard = "c/d/#";
        topics = matchTopics(wildcard, allTopic);
        assertEquals(topics.size(), 1);
        assertTrue(topics.contains("c/d/W"));

        allTopic.add("c/d/X");
        wildcard = "c/d/#";
        topics = matchTopics(wildcard, allTopic);
        assertEquals(topics.size(), 2);
        assertTrue(topics.contains("c/d/W"));
        assertTrue(topics.contains("c/d/X"));

        allTopic.add("c/d");
        wildcard = "c/d/#";
        topics = matchTopics(wildcard, allTopic);
        assertEquals(topics.size(), 2);
        assertTrue(topics.contains("c/d/W"));
        assertTrue(topics.contains("c/d/X"));
    }
}
