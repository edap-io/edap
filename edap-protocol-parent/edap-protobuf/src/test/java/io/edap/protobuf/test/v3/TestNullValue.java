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

package io.edap.protobuf.test.v3;

import io.edap.protobuf.EncodeException;
import io.edap.protobuf.ProtoBuf;
import io.edap.protobuf.test.message.v3.*;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

public class TestNullValue {

    @Test
    void testEncodeBoolNull() throws EncodeException {
        OneBool oneBool = new OneBool();
        byte[] bs = ProtoBuf.toByteArray(oneBool);

        assertArrayEquals(bs, new byte[0]);
    }

    @Test
    void testEncodeInt32Null() throws EncodeException {
        OneInt32 oneInt32 = new OneInt32();
        byte[] bs = ProtoBuf.toByteArray(oneInt32);

        assertArrayEquals(bs, new byte[0]);
    }

    @Test
    void testEncodeUint32Null() throws EncodeException {
        OneUint32 oneUint32 = new OneUint32();
        byte[] bs = ProtoBuf.toByteArray(oneUint32);

        assertArrayEquals(bs, new byte[0]);
    }

    @Test
    void testEncodeFixed32Null() throws EncodeException {
        OneFixed32 oneFixed32 = new OneFixed32();
        byte[] bs = ProtoBuf.toByteArray(oneFixed32);

        assertArrayEquals(bs, new byte[0]);
    }

    @Test
    void testEncodeSint32Null() throws EncodeException {
        OneSint32 oneSint32 = new OneSint32();
        byte[] bs = ProtoBuf.toByteArray(oneSint32);

        assertArrayEquals(bs, new byte[0]);
    }

    @Test
    void testEncodeSfixed32Null() throws EncodeException {
        OneSfixed32 oneSfixed32 = new OneSfixed32();
        byte[] bs = ProtoBuf.toByteArray(oneSfixed32);

        assertArrayEquals(bs, new byte[0]);
    }
}
