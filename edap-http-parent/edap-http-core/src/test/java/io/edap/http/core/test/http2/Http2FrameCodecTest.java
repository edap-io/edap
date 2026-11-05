package io.edap.http.core.test.http2;

import io.edap.buffer.FastBuf;
import io.edap.http.http2.Http2ErrorCode;
import io.edap.http.http2.Http2Exception;
import io.edap.http.http2.Http2Flags;
import io.edap.http.http2.Http2FrameCodec;
import io.edap.http.http2.Http2FrameHeader;
import io.edap.http.http2.Http2FrameType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class Http2FrameCodecTest {

    private static FastBuf buf(int capacity) {
        return new FastBuf(capacity);
    }

    @Test
    public void testWriteThenReadFrameHeader() {
        FastBuf out = buf(64);
        byte[] payload = {1, 2, 3, 4, 5};
        assertTrue(Http2FrameCodec.writeFrame(out, Http2FrameType.DATA,
                Http2Flags.END_STREAM, 7, payload));

        Http2FrameHeader h = Http2FrameCodec.readHeader(out);
        assertNotNull(h);
        assertEquals(Http2FrameType.DATA, h.getType());
        assertEquals(Http2Flags.END_STREAM, h.getFlags());
        assertEquals(7, h.getStreamId());
        assertEquals(payload.length, h.getLength());
        assertTrue(h.hasFlag(Http2Flags.END_STREAM));
    }

    @Test
    public void testHeaderIncompleteReturnsNull() {
        FastBuf b = buf(64);
        b.write(new byte[]{0, 0, 5}, 0, 3);
        assertNull(Http2FrameCodec.readHeader(b), "不足 9 字节必须返回 null 等下次读");
    }

    @Test
    public void testStreamIdReservedBitMasked() {
        FastBuf out = buf(64);
        // 保留位 R 是最高位，必须被屏蔽
        assertTrue(Http2FrameCodec.writeFrame(out, Http2FrameType.HEADERS, 0, 0x80000001, new byte[0]));
        Http2FrameHeader h = Http2FrameCodec.readHeader(out);
        assertNotNull(h);
        assertEquals(1, h.getStreamId());
    }

    @Test
    public void testLengthIs24Bit() {
        // 65536 = 0x010000，长度字段 3 字节大端：0x01 0x00 0x00
        FastBuf out = buf(65536 + 64);
        assertTrue(Http2FrameCodec.writeFrame(out, Http2FrameType.DATA, 0, 1, new byte[65536]));

        Http2FrameHeader h = Http2FrameCodec.readHeader(out);
        assertNotNull(h);
        assertEquals(65536, h.getLength());

        byte[] header = new byte[Http2FrameCodec.FRAME_HEADER_LENGTH];
        assertEquals(Http2FrameCodec.FRAME_HEADER_LENGTH, out.get(header));
        assertEquals(0x01, header[0] & 0xff);
        assertEquals(0x00, header[1] & 0xff);
        assertEquals(0x00, header[2] & 0xff);
    }

    /**
     * ★ 帧不能被劈开：剩余空间不足时整帧不写。
     *
     * <p>HttpResponse.write0 在 socket 满时会中途 writeToChannel，把一帧劈成两半，
     * 对端会报 FRAME_SIZE_ERROR。所以 writeFrame 必须是原子的。</p>
     */
    @Test
    public void testWriteIsAtomicWhenSpaceNotEnough() {
        FastBuf out = buf(64);
        byte[] payload = new byte[64];
        // 只剩 64 字节，装不下 9 + 64
        assertFalse(Http2FrameCodec.writeFrame(out, Http2FrameType.DATA, 0, 1, payload));
        assertEquals(0, out.remain(), "空间不足时一个字节都不能写");
    }

    @Test
    public void testWriteAtomicBoundary() {
        FastBuf out = buf(64);
        byte[] payload = new byte[64 - Http2FrameCodec.FRAME_HEADER_LENGTH];
        assertTrue(Http2FrameCodec.writeFrame(out, Http2FrameType.DATA, 0, 1, payload));
        assertEquals(64, out.remain());
    }

    @Test
    public void testPayloadTooLargeRejected() {
        FastBuf out = buf(16);
        // 长度字段只有 24 位
        assertThrows(Http2Exception.class,
                () -> Http2FrameCodec.writeFrame(out, Http2FrameType.DATA, 0, 1, new byte[0x1000000], 0, 0x1000000));
    }

    @Test
    public void testIntPayload() {
        assertArrayEquals(new byte[]{0, 0, 0, 0x2a}, Http2FrameCodec.intPayload(42));
        assertArrayEquals(new byte[]{(byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff},
                Http2FrameCodec.intPayload(-1));
    }

    @Test
    public void testSkipHeader() {
        FastBuf out = buf(64);
        assertTrue(Http2FrameCodec.writeFrame(out, Http2FrameType.DATA, 0, 1, new byte[]{9, 9}));
        Http2FrameCodec.skipHeader(out);
        byte[] payload = new byte[2];
        assertEquals(2, out.get(payload));
        assertArrayEquals(new byte[]{9, 9}, payload);
    }

    @Test
    public void testFrameTypeNames() {
        assertEquals("DATA", Http2FrameType.name(Http2FrameType.DATA));
        assertEquals("CONTINUATION", Http2FrameType.name(Http2FrameType.CONTINUATION));
        assertTrue(Http2FrameType.name(0x63).startsWith("UNKNOWN"));
    }

    @Test
    public void testErrorCodeNames() {
        assertEquals("PROTOCOL_ERROR", Http2ErrorCode.name(Http2ErrorCode.PROTOCOL_ERROR));
        assertEquals("COMPRESSION_ERROR", Http2ErrorCode.name(Http2ErrorCode.COMPRESSION_ERROR));
    }
}
