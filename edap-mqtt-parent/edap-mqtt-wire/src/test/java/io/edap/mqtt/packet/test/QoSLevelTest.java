package io.edap.mqtt.packet.test;

import io.edap.mqtt.QoSLevel;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class QoSLevelTest {

    @Test
    public void testConstructor() {
        QoSLevel mostOnce = QoSLevel.MOST_ONCE;
        QoSLevel leastOnce = QoSLevel.LEAST_ONCE;
        QoSLevel exactlyOne = QoSLevel.EXACTLY_ONCE;
        QoSLevel reserved = QoSLevel.RESERVED;
        assertEquals(mostOnce.getValue(), 0);
        assertEquals(leastOnce.getValue(), 1);
        assertEquals(exactlyOne.getValue(), 2);
        assertEquals(reserved.getValue(), 3);
    }

    @Test
    public void testFromValue() {
        QoSLevel qos = QoSLevel.fromValue(0);
        assertEquals(qos, QoSLevel.MOST_ONCE);
        qos = QoSLevel.fromValue(1);
        assertEquals(qos, QoSLevel.LEAST_ONCE);
        qos = QoSLevel.fromValue(2);
        assertEquals(qos, QoSLevel.EXACTLY_ONCE);
        qos = QoSLevel.fromValue(3);
        assertEquals(qos, QoSLevel.RESERVED);

        qos = QoSLevel.fromValue(-1);
        assertEquals(qos, QoSLevel.RESERVED);

        qos = QoSLevel.fromValue(4);
        assertEquals(qos, QoSLevel.RESERVED);
    }
}
