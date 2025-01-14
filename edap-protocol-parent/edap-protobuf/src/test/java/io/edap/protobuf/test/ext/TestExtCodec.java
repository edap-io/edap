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

import io.edap.json.Eson;
import io.edap.protobuf.*;
import io.edap.protobuf.ext.AnyCodec;
import io.edap.protobuf.ext.codec.ClassCodec;
import io.edap.protobuf.ext.codec.MessageCodec;
import io.edap.protobuf.ext.codec.MessageFastCodec;
import io.edap.protobuf.ext.codec.NullCodec;
import io.edap.protobuf.internal.ProtoBufOut;
import io.edap.protobuf.model.ProtoBufOption;
import io.edap.protobuf.reader.ByteArrayFastReader;
import io.edap.protobuf.reader.ByteArrayReader;
import io.edap.protobuf.writer.StandardProtoBufWriter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import static io.edap.protobuf.ext.AnyCodec.*;
import static io.edap.protobuf.test.TestUtil.conver2HexStr;
import static org.junit.jupiter.api.Assertions.*;

/**
 * @author : luysh@yonyou.com
 * @date : 2021/10/21
 */
public class TestExtCodec {

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"name\":\"louis\"}",
            "{\"name1\":\"louis\",\"name2\":\"louis\",\"name3\":\"louis\",\"name4\":\"louis\","
                    + "\"name5\":\"louis\",\"name6\":\"louis\",\"name7\":\"louis\",\"name8\":\"louis\","
                    + "\"name9\":\"louis\",\"name10\":\"louis\",\"name11\":\"louis\",\"name12\":\"louis\","
                    + "\"name13\":\"louis\",\"name14\":\"louis\",\"name15\":\"louis\",\"name16\":\"louis\"}"
    })
    public void testCodecHashMap(String value) throws EncodeException, ProtoException {
        Map<String, Object> map = Eson.parseJsonObject(value);
        HashMap<String, Object> hashMap = new HashMap<>();
        hashMap.putAll(map);
        byte[] epb = ProtoBuf.ser(hashMap);
        System.out.println("<====================================================");
        System.out.println(epb.length);
        System.out.println(conver2HexStr(epb));
        System.out.println("====================================================>");
        HashMap<String, Object> nmap = (HashMap<String, Object>)ProtoBuf.der(epb);
        Iterator<String> keys = nmap.keySet().iterator();
        boolean equals = true;
        while (keys.hasNext()) {
            String key = keys.next();
            Object v = hashMap.get(key);
            if (v == null || !v.equals(nmap.get(key))) {
                equals = false;
            }
        }
        assertTrue(equals);
    }

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

        ProtoException thrown = assertThrows(ProtoException.class,
                () -> {
                    msgCodec.decode(reader);
                });
        assertTrue(thrown.getMessage().contains("s Decoder not found!"));

        ByteArrayReader reader2 = new ByteArrayReader(data);
        MessageFastCodec msgCodec2 = new MessageFastCodec();

        ProtoException thrown2 = assertThrows(ProtoException.class,
                () -> {
                    msgCodec2.decode(reader2);
                });
        assertTrue(thrown2.getMessage().contains("s Decoder not found!"));


        ByteArrayReader reader3 = new ByteArrayReader(data);
        MessageCodec msgCodec3 = new MessageCodec();

        ProtoException thrown3 = assertThrows(ProtoException.class,
                () -> {
                    msgCodec3.skip(reader3);
                });
        assertTrue(thrown3.getMessage().contains("s Decoder not found!"));

        ByteArrayReader reader4 = new ByteArrayReader(data);
        MessageFastCodec msgCodec4 = new MessageFastCodec();

        ProtoException thrown4 = assertThrows(ProtoException.class,
                () -> {
                    msgCodec4.skip(reader4);
                });
        assertTrue(thrown4.getMessage().contains("s Decoder not found!"));
    }

    @Test
    void testDecodeNotFoundClass() {
        byte[] data = new byte[]{4,105,111,46,101};
        ByteArrayReader reader = new ByteArrayReader(data);
        ClassCodec codec = new ClassCodec();

        ProtoException thrown = assertThrows(ProtoException.class,
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
        byte[] clsNullData = new byte[]{RANGE_CLASS,-1,-1,-1,-1,-1,-1,-1,-1,-1,1};
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
        byte[] data = new byte[]{(byte)(RANGE_MAX),106,97,118,97,46,117,116,105,108,46,84,114,101,101,77,97,112};
        ByteArrayReader reader = new ByteArrayReader(data);

        ProtoException thrown = assertThrows(ProtoException.class,
                () -> {
                    AnyCodec.decode(reader);
                });
        assertTrue(thrown.getMessage().contains("] hasn't ProtoBufDecoder"));

        thrown = assertThrows(ProtoException.class,
                () -> {
                    reader.reset();
                    AnyCodec.decode(reader, new ProtoBufOption());
                });
        assertTrue(thrown.getMessage().contains("] hasn't ProtoBufDecoder"));

        thrown = assertThrows(ProtoException.class,
                () -> {
                    reader.reset();
                    ProtoBufOption option = new ProtoBufOption();
                    option.setCodecType(CodecType.FAST);
                    AnyCodec.decode(reader, option);
                });
        assertTrue(thrown.getMessage().contains("] hasn't ProtoBufDecoder"));

        thrown = assertThrows(ProtoException.class,
                () -> {
                    reader.reset();
                    ProtoBufOption option = new ProtoBufOption();
                    option.setCodecType(CodecType.FAST);
                    AnyCodec.skipObject(reader);
                });
        assertTrue(thrown.getMessage().contains("] hasn't ProtoBufDecoder"));


        thrown = assertThrows(ProtoException.class,
                () -> {
                    ByteArrayFastReader reader2 = new ByteArrayFastReader(data);
                    reader2.reset();
                    ProtoBufOption option = new ProtoBufOption();
                    option.setCodecType(CodecType.FAST);
                    AnyCodec.skipObject(reader2);
                });
        assertTrue(thrown.getMessage().contains("] hasn't ProtoBufDecoder"));
    }

    @Test
    public void testEncodeWithOption() throws EncodeException {
        Class cls = null;

        ProtoBufWriter writer = new StandardProtoBufWriter(new ProtoBufOut());
        ClassCodec codec = new ClassCodec();
        byte[] clsNullData = new byte[]{-114};
        ProtoBufOption option = new ProtoBufOption();
        AnyCodec.encode(writer, cls, option);
        byte[] data = writer.toByteArray();

        assertArrayEquals(data, clsNullData);

        option.setCodecType(CodecType.FAST);
        writer.reset();
        AnyCodec.encode(writer, cls, option);
        data = writer.toByteArray();
        assertArrayEquals(data, clsNullData);
    }
}
