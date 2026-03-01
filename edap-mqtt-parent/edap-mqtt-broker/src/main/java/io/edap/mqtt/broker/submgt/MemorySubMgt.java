package io.edap.mqtt.broker.submgt;

import io.edap.log.Logger;
import io.edap.log.LoggerManager;
import io.edap.mqtt.QoSLevel;
import io.edap.mqtt.broker.LockPool;
import io.edap.mqtt.broker.MqttBroker;
import io.edap.mqtt.broker.MqttBrokerSession;
import io.edap.mqtt.broker.SubscribeManager;
import io.edap.mqtt.broker.po.SubscribeInfo;
import io.edap.mqtt.packet.SubAck;
import io.edap.mqtt.packet.Subscribe;
import io.edap.mqtt.packet.TopicFilter;
import io.edap.util.StringUtil;

import java.util.*;
import java.util.concurrent.locks.Lock;

import static io.edap.mqtt.broker.utils.WildcardUtils.containsWildcard;
import static io.edap.mqtt.broker.utils.WildcardUtils.matchTopics;

public class MemorySubMgt implements SubscribeManager {

    static Logger LOG = LoggerManager.getLogger(MemorySubMgt.class);

    private Set<String> TOPICS = new HashSet<>();

    private Map<String, List<SubscribeInfo>> TOPIC_TO_SUBSCRIBE_INFOS = new HashMap<>();

    private Map<String, MqttBrokerSession> WILDCARD_TO_SESSION = new HashMap();

    public void checkAndAddTopic(String topic) {
        TOPICS.add(topic);
    }

    public SubAck subscribe(Subscribe subscribe, MqttBrokerSession session) {
        LOG.info("client {} subscribe packetIdentifier {}",
                l -> l.arg(session.getClientId()).arg(subscribe.getPacketIdentifier()));
        SubAck subAck = new SubAck();
        LockPool lockPool = ((MqttBroker)session.getServer()).getLockPool();
        subAck.setPacketIdentifier(subscribe.getPacketIdentifier());
        List<Integer> respCodes = new ArrayList<>();
        int respCode;
        for (TopicFilter tf : subscribe.getTopicFilterList()) {
            QoSLevel level = QoSLevel.fromValue(tf.getSubscriptionOptions());
            if (level == QoSLevel.RESERVED) {
                respCode = 143;
                respCodes.add(respCode);
                continue;
            } else {
                respCode = level.getValue();
            }
            if (StringUtil.isEmpty(tf.getTopicFilter())) {
                respCode = 143;
                respCodes.add(respCode);
                continue;
            }
            String topic = tf.getTopicFilter();
            boolean hasWildcard = containsWildcard(tf.getTopicFilter());
            if (hasWildcard) {
                WILDCARD_TO_SESSION.put(topic, session);
                List<String> topis = matchTopics(topic, TOPICS);
                for (String mt : topis) {
                    subscribeTopic(lockPool, mt, session, level);
                }
            } else {
                if (!TOPICS.contains(topic)) {
                    TOPICS.add(topic);
                }
                subscribeTopic(lockPool, topic, session, level);
            }
            respCodes.add(respCode);
        }
        subAck.setRespCodes(respCodes);
        return subAck;
    }

    private void subscribeTopic(LockPool lockPool, String topic, MqttBrokerSession session, QoSLevel level) {
        Lock lock = lockPool.getSubTopicLock(topic);
        lock.lock();
        try {
            List<SubscribeInfo> sis = TOPIC_TO_SUBSCRIBE_INFOS.get(topic);
            if (sis == null) {
                sis = new ArrayList<>();
                SubscribeInfo si = new SubscribeInfo();
                si.setTopicFilter(topic);
                si.setQoSLevel(level);
                si.setSession(session);
                si.setClientId(session.getClientId());
                sis.add(si);
                TOPIC_TO_SUBSCRIBE_INFOS.put(topic, sis);
            } else {
                boolean contain = false;
                for (SubscribeInfo si : sis) {
                    if (si.getClientId().equals(session.getClientId())) {
                        si.setQoSLevel(level);
                        si.setTopicFilter(topic);
                        si.setSession(session);
                        contain = true;
                    }
                }
                if (!contain) {
                    SubscribeInfo si = new SubscribeInfo();
                    si.setTopicFilter(topic);
                    si.setQoSLevel(level);
                    si.setSession(session);
                    si.setClientId(session.getClientId());
                    sis.add(si);
                }
            }
        } finally {
            lock.unlock();
        }
    }
}
