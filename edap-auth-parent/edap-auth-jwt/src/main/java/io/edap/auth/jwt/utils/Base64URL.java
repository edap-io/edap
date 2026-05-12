package io.edap.auth.jwt.utils;

import io.edap.json.JsonWriter;
import io.edap.util.ByteArrayBuilder;
import io.edap.util.StringUtil;

import java.nio.charset.StandardCharsets;

public class Base64URL {

    private static final byte[] BASE64URL_BYTES = new byte[]{
            'A', 'B', 'C', 'D', 'E', 'F', 'G', 'H', 'I', 'J', 'K', 'L', 'M',
            'N', 'O', 'P', 'Q', 'R', 'S', 'T', 'U', 'V', 'W', 'X', 'Y', 'Z',
            'a', 'b', 'c', 'd', 'e', 'f', 'g', 'h', 'i', 'j', 'k', 'l', 'm',
            'n', 'o', 'p', 'q', 'r', 's', 't', 'u', 'v', 'w', 'x', 'y', 'z',
            '0', '1', '2', '3', '4', '5', '6', '7', '8', '9', '-', '_'
    };

    private static final int[] BASE64URL_VALUES = new int[128];

    static {
        for (int i = 0; i < BASE64URL_VALUES.length; i++) {
            BASE64URL_VALUES[i] = -1;
        }
        for (int i=0;i<BASE64URL_BYTES.length;i++) {
            BASE64URL_VALUES[BASE64URL_BYTES[i]] = i;
        }
    }

    public static String encode(byte[] bytes) {
        return encode(bytes, 0, bytes.length);
    }

    public static String encode(byte[] bytes, int offset, int len) {
        int mod = len % 3;
        int end = len - mod;
        int i = offset;
        byte[] buf;
        int l = len/3 * 4;
        if (mod == 0) {
            buf = new byte[l];
        } else if (mod == 1) {
            buf = new byte[l + 2];
        } else {
            buf = new byte[l + 3];
        }
        int count = 0;
        int v;
        byte[] _buf = BASE64URL_BYTES;
        while (i < end) {
            v = (bytes[i++] & 0xFF) << 16 | (bytes[i++] & 0xFF) << 8 | bytes[i++] & 0xFF;
            buf[count++] = _buf[(v >> 18) & 0x3f];
            buf[count++] = _buf[(v >> 12) & 0x3f];
            buf[count++] = _buf[(v >> 6) & 0x3f];
            buf[count++] = _buf[v & 0x3f];
        }
        if (mod == 1) {
            int b1 = bytes[i] & 0xFF;
            buf[count++] = _buf[(b1 >> 2) & 0x3f];
            buf[count++] = _buf[(b1 << 4) & 0x3f];
        } else if (mod == 2) {
            int b1 = bytes[i++] & 0xFF;
            int b2 = bytes[i] & 0xFF;
            buf[count++] = _buf[(b1 >> 2) & 0x3f];
            buf[count++] = _buf[((b1 << 4) | (b2 >> 4)) & 0x3f];
            buf[count++] = _buf[(b2 << 2) & 0x3f];
        }
        if (count != buf.length) {
            System.arraycopy(buf, 0, buf, 0,  count);
        }
        return StringUtil.fastInstance(buf, (byte)0);
    }

    public static void encodeTo(ByteArrayBuilder builder, JsonWriter writer) {
        int len = writer.size();
        int mod = len % 3;
        int end = len - mod;
        int l = len/3 * 4;
        if (mod == 1) {
            l += 2;
        } else if (mod == 2) {
            l += 3;
        }
        byte[] bytes = writer.getBuf();
        builder.ensureCapacity(builder.length() + l);
        byte[] buf = builder.getValue();
        byte[] _buf = BASE64URL_BYTES;
        int count = builder.length();
        int i = 0;
        int v;
        while (i<end) {
            v = (bytes[i++] & 0xFF) << 16 | (bytes[i++] & 0xFF) << 8 | bytes[i++] & 0xFF;
            buf[count++] = _buf[(v >> 18) & 0x3f];
            buf[count++] = _buf[(v >> 12) & 0x3f];
            buf[count++] = _buf[(v >> 6) & 0x3f];
            buf[count++] = _buf[v & 0x3f];
        }
        if (mod == 1) {
            int b1 = bytes[i] & 0xFF;
            buf[count++] = _buf[(b1 >> 2) & 0x3f];
            buf[count++] = _buf[(b1 << 4) & 0x3f];
        } else if (mod == 2) {
            int b1 = bytes[i++] & 0xFF;
            int b2 = bytes[i] & 0xFF;
            buf[count++] = _buf[(b1 >> 2) & 0x3f];
            buf[count++] = _buf[((b1 << 4) | (b2 >> 4)) & 0x3f];
            buf[count++] = _buf[(b2 << 2) & 0x3f];
        }
        builder.setLength(builder.length() + l);
    }

    public static void encodeTo(ByteArrayBuilder builder, byte[] bytes) {
        int len = bytes.length;
        int mod = len % 3;
        int end = len - mod;
        int l = len/3 * 4;
        if (mod == 1) {
            l += 2;
        } else if (mod == 2) {
            l += 3;
        }
        builder.ensureCapacity(l + builder.length());
        byte[] buf = builder.getValue();
        byte[] _buf = BASE64URL_BYTES;
        int count = builder.length();
        int i = 0;
        int v;
        while (i < end) {
            v = (bytes[i++] & 0xFF) << 16 | (bytes[i++] & 0xFF) << 8 | bytes[i++] & 0xFF;
            buf[count++] = _buf[(v >> 18) & 0x3f];
            buf[count++] = _buf[(v >> 12) & 0x3f];
            buf[count++] = _buf[(v >> 6)  & 0x3f];
            buf[count++] = _buf[v & 0x3f];
        }
        if (mod == 1) {
            int b1 = bytes[i] & 0xFF;
            buf[count++] = _buf[(b1 >> 2) & 0x3f];
            buf[count]   = _buf[(b1 << 4) & 0x3f];
        } else if (mod == 2) {
            int b1 = bytes[i++] & 0xFF;
            int b2 = bytes[i] & 0xFF;
            buf[count++] = _buf[(b1 >> 2) & 0x3f];
            buf[count++] = _buf[((b1 << 4) | (b2 >> 4)) & 0x3f];
            buf[count]   = _buf[(b2 << 2) & 0x3f];
        }
        builder.setLength(builder.length() + l);
    }

    public static String decode(String str) {
        int len = str.length();
        int mod = len % 4;
        int end = len - mod;
        int i = 0;
        int v;
        int l = end/4 * 3;
        if (mod == 1) {
            throw new RuntimeException("错误的Base64字符串");
        } else if (mod == 2) {
            l += 1;
        } else {
            l += 2;
        }
        byte[] buf = new byte[l];
        int count = 0;
        while (i < end) {
            v = (BASE64URL_VALUES[str.charAt(i++)] & 0x3F) << 18
                    | (BASE64URL_VALUES[str.charAt(i++)] & 0x3F) << 12
                    | (BASE64URL_VALUES[str.charAt(i++)] & 0x3F) << 6
                    | (BASE64URL_VALUES[str.charAt(i++)] & 0x3F);
            buf[count++] = (byte) (v >>> 16);
            buf[count++] = (byte) (v >>> 8);
            buf[count++] = (byte) (v);
        }
        if (mod == 2) {
            v = (BASE64URL_VALUES[str.charAt(i++)] & 0x3F) << 2 | (BASE64URL_VALUES[str.charAt(i)] & 0x3F) >> 4;
            buf[count] = (byte) (v);
        } else if (mod == 3) {
            v = (BASE64URL_VALUES[str.charAt(i++)] & 0x3F) << 10 | (BASE64URL_VALUES[str.charAt(i++)] & 0x3F) << 4
                    | (BASE64URL_VALUES[str.charAt(i)] & 0x3F) >> 2;
            buf[count++] = (byte) (v >>> 8);
            buf[count]   = (byte) (v);
        }
        return new String(buf, StandardCharsets.UTF_8);
    }
}
