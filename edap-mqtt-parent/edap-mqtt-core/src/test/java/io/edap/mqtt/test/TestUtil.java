package io.edap.mqtt.test;

import io.edap.buffer.FastBuf;
import io.edap.mqtt.IntegerToLongException;

import java.util.Random;

public class TestUtil {

    public static void writeMqttVarInt(FastBuf buf, int len) {
        int val = len;
        if ((val & ~0x7F) == 0) {
            buf.write((byte)(val & 0x7F));
        } else {
            byte b1 = (byte) ((val & 0x7F) | 0x80);
            val >>>= 7;
            if ((val & ~0x7F) == 0) {
                buf.write(b1);
                buf.write((byte) val);
            } else {
                byte b2 = (byte) ((val & 0x7F) | 0x80);
                val >>>= 7;
                if ((val & ~0x7F) == 0) {
                    buf.write(b1);
                    buf.write(b2);
                    buf.write((byte) val);
                } else {
                    byte b3 = (byte) ((val & 0x7F) | 0x80);
                    val >>>= 7;
                    if ((val & ~0x7F) == 0) {
                        buf.write(b1);
                        buf.write(b2);
                        buf.write(b3);
                        buf.write((byte) val);
                    } else {
                        throw new IntegerToLongException("Integer " + len + " too big");
                    }
                }
            }
        }
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
