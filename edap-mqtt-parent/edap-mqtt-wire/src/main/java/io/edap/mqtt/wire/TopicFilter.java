package io.edap.mqtt.wire;

public class TopicFilter {

    private String topicFilter;
    private int subscriptionOptions;

    public String getTopicFilter() {
        return topicFilter;
    }

    public void setTopicFilter(String topicFilter) {
        this.topicFilter = topicFilter;
    }

    public int getSubscriptionOptions() {
        return subscriptionOptions;
    }

    public void setSubscriptionOptions(int subscriptionOptions) {
        this.subscriptionOptions = subscriptionOptions;
    }
}
