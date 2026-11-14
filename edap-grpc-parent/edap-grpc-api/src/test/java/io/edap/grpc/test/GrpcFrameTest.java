/*
 * Copyright 2020 The edap Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package io.edap.grpc.test;

import io.edap.grpc.GrpcException;
import io.edap.grpc.GrpcFrame;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class GrpcFrameTest {

    @Test
    public void testEncodeHeader() {
        byte[] message = {1, 2, 3};
        byte[] frame = GrpcFrame.encode(message);
        assertEquals(GrpcFrame.HEADER_LENGTH + 3, frame.length);
        // 压缩标志为 0，长度为大端的 3
        assertArrayEquals(new byte[]{0, 0, 0, 0, 3}, Arrays.copyOf(frame, GrpcFrame.HEADER_LENGTH));
        assertArrayEquals(message, Arrays.copyOfRange(frame, GrpcFrame.HEADER_LENGTH, frame.length));
    }

    @Test
    public void testEncodeEmptyMessage() {
        byte[] frame = GrpcFrame.encode(new byte[0]);
        assertEquals(GrpcFrame.HEADER_LENGTH, frame.length);
        assertEquals(0, GrpcFrame.decodeLength(frame, 0));
        assertFalse(GrpcFrame.isCompressed(frame, 0));
    }

    @Test
    public void testEncodeNullMessage() {
        byte[] frame = GrpcFrame.encode(null);
        assertEquals(GrpcFrame.HEADER_LENGTH, frame.length);
        assertEquals(0, GrpcFrame.decodeLength(frame, 0));
    }

    @Test
    public void testDecodeLengthBigEndian() {
        byte[] frame = new byte[]{0, 0x01, 0x02, 0x03, 0x04, 9, 9, 9};
        assertEquals(0x01020304, GrpcFrame.decodeLength(frame, 0));
    }

    @Test
    public void testDecodeLengthMaxInt() {
        byte[] frame = new byte[]{0, 0x7F, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF};
        assertEquals(Integer.MAX_VALUE, GrpcFrame.decodeLength(frame, 0));
    }

    @Test
    public void testDecodeLengthAtOffset() {
        byte[] buffer = new byte[]{7, 7, 0, 0x00, 0x00, 0x10, 0x00};
        assertEquals(4096, GrpcFrame.decodeLength(buffer, 2));
    }

    @Test
    public void testCompressedFlag() {
        byte[] frame = new byte[]{1, 0, 0, 0, 0};
        assertTrue(GrpcFrame.isCompressed(frame, 0));
        byte[] identity = new byte[]{0, 0, 0, 0, 0};
        assertFalse(GrpcFrame.isCompressed(identity, 0));
    }

    @Test
    public void testEncodeWithOffset() {
        byte[] message = {9, 9, 1, 2, 3, 9};
        byte[] frame = GrpcFrame.encode(message, 2, 3);
        assertEquals(GrpcFrame.HEADER_LENGTH + 3, frame.length);
        assertEquals(3, GrpcFrame.decodeLength(frame, 0));
        assertArrayEquals(new byte[]{1, 2, 3}, Arrays.copyOfRange(frame, GrpcFrame.HEADER_LENGTH, frame.length));
    }

    @Test
    public void testDecodeLengthIncomplete() {
        assertThrows(GrpcException.class, () -> GrpcFrame.decodeLength(new byte[]{0, 0, 0}, 0));
        assertThrows(GrpcException.class, () -> GrpcFrame.decodeLength(null, 0));
    }

    @Test
    public void testCheckLength() {
        GrpcFrame.checkLength(GrpcFrame.MAX_MESSAGE_SIZE);
        assertThrows(GrpcException.class, () -> GrpcFrame.checkLength(GrpcFrame.MAX_MESSAGE_SIZE + 1));
    }

    @Test
    public void testNegativeLength() {
        assertThrows(GrpcException.class, () -> GrpcFrame.encode(new byte[1], 0, -1));
    }
}
