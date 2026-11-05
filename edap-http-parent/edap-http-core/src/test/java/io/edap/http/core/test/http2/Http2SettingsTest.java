package io.edap.http.core.test.http2;

import io.edap.http.http2.Http2ErrorCode;
import io.edap.http.http2.Http2Exception;
import io.edap.http.http2.Http2Settings;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class Http2SettingsTest {

    private static byte[] entry(int id, long value) {
        return new byte[]{
                (byte) (id >>> 8), (byte) id,
                (byte) (value >>> 24), (byte) (value >>> 16), (byte) (value >>> 8), (byte) value
        };
    }

    @Test
    public void testDefaults() {
        Http2Settings s = new Http2Settings();
        assertEquals(4096, s.getHeaderTableSize());
        assertEquals(0, s.getEnablePush(), "服务端不启用 server push");
        assertEquals(65535, s.getInitialWindowSize());
        assertEquals(16384, s.getMaxFrameSize());
    }

    @Test
    public void testApplyAllSixParameters() {
        Http2Settings s = new Http2Settings();
        byte[] payload = new byte[36];
        int p = 0;
        for (byte[] e : new byte[][]{
                entry(Http2Settings.HEADER_TABLE_SIZE, 8192),
                entry(Http2Settings.ENABLE_PUSH, 0),
                entry(Http2Settings.MAX_CONCURRENT_STREAMS, 100),
                entry(Http2Settings.INITIAL_WINDOW_SIZE, 65536),
                entry(Http2Settings.MAX_FRAME_SIZE, 32768),
                entry(Http2Settings.MAX_HEADER_LIST_SIZE, 16384)}) {
            System.arraycopy(e, 0, payload, p, 6);
            p += 6;
        }
        s.apply(payload, 0, payload.length);

        assertEquals(8192, s.getHeaderTableSize());
        assertEquals(0, s.getEnablePush());
        assertEquals(100, s.getMaxConcurrentStreams());
        assertEquals(65536, s.getInitialWindowSize());
        assertEquals(32768, s.getMaxFrameSize());
        assertEquals(16384, s.getMaxHeaderListSize());
    }

    @Test
    public void testUnknownParameterIgnored() {
        Http2Settings s = new Http2Settings();
        byte[] payload = entry(0x63, 12345);
        s.apply(payload, 0, payload.length);
        assertEquals(4096, s.getHeaderTableSize(), "未知参数必须被忽略");
    }

    @Test
    public void testPayloadLengthMustBeMultipleOfSix() {
        Http2Settings s = new Http2Settings();
        Http2Exception e = assertThrows(Http2Exception.class,
                () -> s.apply(new byte[5], 0, 5));
        assertEquals(Http2ErrorCode.FRAME_SIZE_ERROR, e.getErrorCode());
    }

    @Test
    public void testEnablePushInvalidValue() {
        Http2Settings s = new Http2Settings();
        byte[] payload = entry(Http2Settings.ENABLE_PUSH, 2);
        Http2Exception e = assertThrows(Http2Exception.class,
                () -> s.apply(payload, 0, payload.length));
        assertEquals(Http2ErrorCode.PROTOCOL_ERROR, e.getErrorCode());
    }

    @Test
    public void testMaxFrameSizeOutOfRange() {
        Http2Settings s = new Http2Settings();
        byte[] tooSmall = entry(Http2Settings.MAX_FRAME_SIZE, 1024);
        assertThrows(Http2Exception.class, () -> s.apply(tooSmall, 0, tooSmall.length));

        byte[] tooBig = entry(Http2Settings.MAX_FRAME_SIZE, 16777216);
        assertThrows(Http2Exception.class, () -> s.apply(tooBig, 0, tooBig.length));
    }

    @Test
    public void testInitialWindowSizeOverflow() {
        Http2Settings s = new Http2Settings();
        byte[] payload = entry(Http2Settings.INITIAL_WINDOW_SIZE, 0x80000000L);
        Http2Exception e = assertThrows(Http2Exception.class,
                () -> s.apply(payload, 0, payload.length));
        assertEquals(Http2ErrorCode.FLOW_CONTROL_ERROR, e.getErrorCode());
    }

    /**
     * 编码只输出与默认值不同的参数（RFC 9113 §6.5）。
     */
    @Test
    public void testEncodeOnlyNonDefaultParameters() {
        Http2Settings s = new Http2Settings();
        assertEquals(0, s.encode().length, "全默认值时 SETTINGS 载荷为空");

        s.setMaxFrameSize(32768);
        assertArrayEquals(entry(Http2Settings.MAX_FRAME_SIZE, 32768), s.encode());

        s.setInitialWindowSize(65536);
        assertEquals(12, s.encode().length);
    }

    @Test
    public void testEncodeRoundTrip() {
        Http2Settings src = new Http2Settings();
        src.setHeaderTableSize(2048);
        src.setMaxConcurrentStreams(50);
        src.setInitialWindowSize(100000);
        src.setMaxFrameSize(20000);
        src.setMaxHeaderListSize(4096);

        Http2Settings dst = new Http2Settings();
        byte[] payload = src.encode();
        dst.apply(payload, 0, payload.length);

        assertEquals(2048, dst.getHeaderTableSize());
        assertEquals(50, dst.getMaxConcurrentStreams());
        assertEquals(100000, dst.getInitialWindowSize());
        assertEquals(20000, dst.getMaxFrameSize());
        assertEquals(4096, dst.getMaxHeaderListSize());
    }
}
