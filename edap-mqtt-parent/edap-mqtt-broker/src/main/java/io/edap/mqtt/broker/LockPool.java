package io.edap.mqtt.broker;

import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public class LockPool {

    private static Lock[] SUB_TOPIC_LOCKS;

    private static Lock TOPIC_LOCK = new ReentrantLock();

    public LockPool() {
        SUB_TOPIC_LOCKS = new ReentrantLock[128];
        for (int i=0;i<SUB_TOPIC_LOCKS.length;i++) {
            SUB_TOPIC_LOCKS[i] = new ReentrantLock();
        }
    }

    public Lock getSubTopicLock(String topic) {
        return SUB_TOPIC_LOCKS[topic.hashCode() % SUB_TOPIC_LOCKS.length];
    }

    public Lock getTopicLock() {
        return TOPIC_LOCK;
    }
}
