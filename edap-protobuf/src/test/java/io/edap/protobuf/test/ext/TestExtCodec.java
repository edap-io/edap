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

package io.edap.protobuf.test.ext;

import io.edap.protobuf.EncodeException;
import io.edap.protobuf.ProtoBufException;
import io.edap.protobuf.ProtoBufWriter;
import io.edap.protobuf.ext.AnyCodec;
import io.edap.protobuf.ext.codec.ClassCodec;
import io.edap.protobuf.ext.codec.MessageCodec;
import io.edap.protobuf.ext.codec.NullCodec;
import io.edap.protobuf.internal.ProtoBufOut;
import io.edap.protobuf.reader.ByteArrayReader;
import io.edap.protobuf.writer.StandardProtoBufWriter;
import io.edap.protobuf.writer.StandardReverseWriter;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * @author : luysh@yonyou.com
 * @date : 2021/10/21
 */
public class TestExtCodec {

    @Test
    void testCodecNullEx() {
        EncodeException thrown = assertThrows(EncodeException.class,
                () -> {
                    NullCodec nullCodec = new NullCodec();
                    ProtoBufWriter writer = new StandardProtoBufWriter(new ProtoBufOut());
                    nullCodec.encode(writer, 5);
                });
        assertTrue(thrown.getMessage().contains("Object is not null"));
    }

    @Test
    void testMessageCodecEx() {
        byte[] data = new byte[]{4,105,111,46,101};

        ByteArrayReader reader = new ByteArrayReader(data);
        MessageCodec msgCodec = new MessageCodec();

        ProtoBufException thrown = assertThrows(ProtoBufException.class,
                () -> {
                    msgCodec.decode(reader);
                });
        assertTrue(thrown.getMessage().contains("s Decoder not found!"));
    }

    @Test
    void testDecodeNotFoundClass() {
        byte[] data = new byte[]{4,105,111,46,101};
        ByteArrayReader reader = new ByteArrayReader(data);
        ClassCodec codec = new ClassCodec();

        ProtoBufException thrown = assertThrows(ProtoBufException.class,
                () -> {
                    codec.decode(reader);
                });
        assertTrue(thrown.getMessage().contains("Class " + new String(new byte[]{105,111,46,101}) + " not found"));
    }

    @Test
    void testDecodecNullClass() {
        Class cls = null;

        ProtoBufWriter writer = new StandardProtoBufWriter(new ProtoBufOut());
        ClassCodec codec = new ClassCodec();
        byte[] clsNullData = new byte[]{76,-1,-1,-1,-1,-1,-1,-1,-1,-1,1};
        try {
            codec.encode(writer, cls);

            byte[] data = writer.toByteArray();

            assertArrayEquals(data, clsNullData);
            System.out.print('[');
            for (int i=0;i<data.length;i++) {
                System.out.print(data[i]);
                if (i < data.length - 1) {
                    System.out.print(',');
                }
            }
            System.out.println(']');
        } catch (Exception e) {
            e.printStackTrace();
        }

        writer = new StandardReverseWriter(new ProtoBufOut());
        writer.reset();
        try {
            codec.encode(writer, cls);

            byte[] data = writer.toByteArray();

            assertArrayEquals(data, clsNullData);
            System.out.print('[');
            for (int i=0;i<data.length;i++) {
                System.out.print(data[i]);
                if (i < data.length - 1) {
                    System.out.print(',');
                }
            }
            System.out.println(']');
        } catch (Exception e) {
            e.printStackTrace();
        }

        ByteArrayReader reader = new ByteArrayReader(clsNullData, 1, clsNullData.length-1);
        try {
            Class decodeCls = codec.decode(reader);

            System.out.println("class is " + decodeCls);
            assertNull(decodeCls);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Test
    void testAnyCodec() {
        byte[] data = new byte[]{127,106,97,118,97,46,117,116,105,108,46,84,114,101,101,77,97,112};
        ByteArrayReader reader = new ByteArrayReader(data);

        ProtoBufException thrown = assertThrows(ProtoBufException.class,
                () -> {
                    AnyCodec.decode(reader);
                });
        assertTrue(thrown.getMessage().contains("] hasn't ProtoBufDecoder"));
    }
}
