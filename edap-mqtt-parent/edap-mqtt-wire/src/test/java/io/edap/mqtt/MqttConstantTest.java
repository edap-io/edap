package io.edap.mqtt;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class MqttConstantTest {

    @Test
    public void testTwoByteMaxValue() {

        assertEquals(MqttConstant.TWO_BYTE_INT_MAX_VALUE, 65535);
    }
}
