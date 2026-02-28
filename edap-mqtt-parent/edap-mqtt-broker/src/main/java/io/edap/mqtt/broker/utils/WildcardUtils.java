package io.edap.mqtt.broker.utils;

import io.edap.util.StringUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class WildcardUtils {

    private WildcardUtils() {}

    /**
     * 判断一个topic名称是否包含通配符
     * @param topic 订阅主题的过滤字符串
     * @return 如果包含通配符返回true，否则返回false
     */
    public static boolean containsWildcard(String topic) {
        if (StringUtil.isEmpty(topic)) {
            return false;
        }
        int index = topic.indexOf("+");
        if (index >= 0) {
            if (index > 0) {
                if (topic.charAt(index - 1) != '/') {
                    return false;
                }
            }
            if (index < topic.length() - 1) {
                if (topic.charAt(index + 1) != '/') {
                    return false;
                }
            }
            return true;
        }

        if (topic.length() > 0 && topic.charAt(topic.length()-1) == '#') {
            if (topic.length() > 1) {
                if (topic.charAt(topic.length() - 2) == '/') {
                    return true;
                } else {
                    return false;
                }
            } else {
                return true;
            }
        }
        return false;
    }

    public static List<String> matchTopics(String wildcard, Set<String> allTopic) {
        List<String> topics = new ArrayList<>();
        WildcardParseResult wildcardResult = splitWildcard(wildcard, '/');
        List<String> wildcardItems = wildcardResult.items;
        List<String> items;
        for (String topic :  allTopic) {
            items = split(topic, '/');
            if (wildcardResult.singleLevelMatch && singleMatch(items, wildcardItems)) {
                topics.add(topic);
                continue;
            }
            if (wildcardResult.multiLevelMatch && multiMatch(items, wildcardItems)) {
                topics.add(topic);
            }
        }
        return topics;
    }

    private static boolean multiMatch(List<String> topicItems, List<String> wildcardItems) {
        int len = wildcardItems.size();
        if (topicItems.size() < len) {
            return false;
        }
        int end = len - 1;
        boolean match = true;
        for (int i=0;i<end;i++) {
            String wildcardItem = wildcardItems.get(i);
            String topicItem    = topicItems.get(i);
            if (wildcardItem.equals(topicItem)) {
                continue;
            }
            if (!"+".equals(wildcardItem)) {
                match = false;
            }
        }

        return match;
    }

    private static boolean singleMatch(List<String> topicItems, List<String> wildcardItems) {
        int size = wildcardItems.size();
        if (topicItems.size() != size) {
            return false;
        }
        boolean match = true;
        for (int i=0;i<size;i++) {
            String wildcardItem = wildcardItems.get(i);
            String topicItem    = topicItems.get(i);
            if (wildcardItem.equals(topicItem)) {
                continue;
            }
            if (!"+".equals(wildcardItem)) {
                match = false;
            }
        }

        return match;
    }

    public static WildcardParseResult splitWildcard(String wildcard, char sep) {
        WildcardParseResult result = new WildcardParseResult();
        List<String> items = new ArrayList<>();
        int start = 0;
        int index = wildcard.indexOf(sep, start);
        while (index != -1) {
            String item = wildcard.substring(start, index);
            if ("+".equals(item)) {
                result.singleLevelMatch = true;
            }
            items.add(item);
            start++;
            index = wildcard.indexOf(sep, start);
        }
        String item = wildcard.substring(start);
        if ("+".equals(item)) {
            result.singleLevelMatch = true;
        }
        items.add(item);
        if ("#".equals(items.get(items.size() - 1))) {
            result.multiLevelMatch = true;
        }
        result.items = items;
        return result;
    }

    public static List<String> split(String wildcard, char sep) {
        List<String> items = new ArrayList<>();
        int start = 0;
        int index = wildcard.indexOf(sep, start);
        while (index != -1) {
            items.add(wildcard.substring(start, index));
            start++;
            index = wildcard.indexOf(sep, start);
        }
        items.add(wildcard.substring(start));

        return items;
    }

    static class WildcardParseResult {
        private List<String> items;
        private boolean      singleLevelMatch;
        private boolean      multiLevelMatch;
    }
}
