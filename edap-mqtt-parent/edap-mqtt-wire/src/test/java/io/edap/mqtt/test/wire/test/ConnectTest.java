package io.edap.mqtt.test.wire.test;

import io.edap.mqtt.wire.Connect;
import io.edap.mqtt.wire.ControlPacketType;
import io.edap.mqtt.wire.ProtocolLevel;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class ConnectTest {

    @Test
    public void testConstructor() {
        Connect connect = new Connect(52);
        assertEquals(connect.getType(), ControlPacketType.CONNECT);
    }

    @Test
    public void testProtocolName() {
        Connect connect = new Connect(52);
        connect.setProtocolName("mqtt");
        assertEquals(connect.getProtocolName(), "mqtt");
    }

    @Test
    public void testProtocolLevel() {
        Connect connect = new Connect(52);
        connect.setProtocolLevel(ProtocolLevel.VERSION_3_1_1);
        assertEquals(connect.getProtocolLevel(), ProtocolLevel.VERSION_3_1_1);
    }

    @Test
    public void testUserNameFlag() {
        Connect connect = new Connect(52);
        int userNameFlag = new Random().nextInt();
        connect.setUserNameFlag(userNameFlag);
        assertEquals(connect.getUserNameFlag(), userNameFlag);
    }

    @Test
    public void testPasswordFlag() {
        Connect connect = new Connect(52);
        int passwordFlag = new Random().nextInt();
        connect.setPasswordFlag(passwordFlag);
        assertEquals(connect.getPasswordFlag(), passwordFlag);
    }

    @Test
    public void testWillRetain() {
        Connect connect = new Connect(52);
        int willRetain = new Random().nextInt();
        connect.setWillRetain(willRetain);
        assertEquals(connect.getWillRetain(), willRetain);
    }

    @Test
    public void testWillQoS() {
        Connect connect = new Connect(52);
        int willQoS = new Random().nextInt();
        connect.setWillQoS(willQoS);
        assertEquals(connect.getWillQoS(), willQoS);
    }

    @Test
    public void testWillFlag() {
        Connect connect = new Connect(52);
        int willFlag = new Random().nextInt();
        connect.setWillFlag(willFlag);
        assertEquals(connect.getWillFlag(), willFlag);
    }

    @Test
    public void testCleanSessionFlag() {
        Connect connect = new Connect(52);
        int sessionFlag = new Random().nextInt();
        connect.setCleanSessionFlag(sessionFlag);
        assertEquals(connect.getCleanSessionFlag(), sessionFlag);
    }

    @Test
    public void testReserved() {
        Connect connect = new Connect(52);
        int reserved = new Random().nextInt();
        connect.setReserved(reserved);
        assertEquals(connect.getReserved(), reserved);
    }

    @Test
    public void testKeepAlive() {
        Connect connect = new Connect(52);
        int keepAlive = new Random().nextInt();
        connect.setKeepAlive(keepAlive);
        assertEquals(connect.getKeepAlive(), keepAlive);
    }

    @Test
    public void testClientIdentifier() {
        Connect connect = new Connect(52);
        String clientIdentifier = randomStr(new Random().nextInt(50));
        connect.setClientIdentifier(clientIdentifier);
        assertEquals(connect.getClientIdentifier(), clientIdentifier);
    }

    @Test
    public void testTopic() {
        Connect connect = new Connect(52);
        String topic = randomStr(new Random().nextInt(50));
        connect.setTopic(topic);
        assertEquals(connect.getTopic(), topic);
    }

    @Test
    public void testMessage() {
        Connect connect = new Connect(52);
        String message = randomStr(new Random().nextInt(50));
        connect.setMessage(message);
        assertEquals(connect.getMessage(), message);
    }

    @Test
    public void testUserName() {
        Connect connect = new Connect(52);
        String userName = randomStr(new Random().nextInt(50));
        connect.setUserName(userName);
        assertEquals(connect.getUserName(), userName);
    }

    @Test
    public void testPassword() {
        Connect connect = new Connect(52);
        String password = randomStr(new Random().nextInt(50));
        connect.setPassword(password);
        assertEquals(connect.getPassword(), password);
    }

    public static String randomStr(int count) {
        int max = Byte.MAX_VALUE;
        Random random = new Random();
        StringBuilder sb = new StringBuilder();
        for (int i=0;i<count;i++) {
            String s;
            while (true) {
                try {
                    s = new String(new byte[]{(byte)random.nextInt(max), (byte)random.nextInt(max)}, "utf-8");
                    break;
                } catch (Exception e) {

                }
            }
            sb.append(s);
        }
        return sb.toString();
    }
}
