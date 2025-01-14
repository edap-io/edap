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

import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import io.edap.json.JsonArray;
import io.edap.protobuf.EncodeException;
import io.edap.protobuf.ProtoBuf;
import io.edap.protobuf.ProtoException;
import io.edap.protobuf.test.message.v3.ListBytes;
import io.edap.protobuf.test.message.v3.ListBytesNoAccess;
import io.edap.protobuf.test.message.v3.ListBytesOuterClass;
import io.edap.util.ClazzUtil;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.UnsupportedEncodingException;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class TestListBytes {

    @ParameterizedTest
    @ValueSource(strings = {
            "[\"abcdefghijklmn\",\"中文内容\"]",
            "[\"中文内容\",\"abcdefghijklmn\"]"
    })
    void testEncode(String v) throws UnsupportedEncodingException, EncodeException {
        List<byte[]> vs = new ArrayList<>();
        List<ByteString> pvs = new ArrayList<>();
        JsonArray jvs = JsonArray.parseArray(v);
        for (int i=0;i<jvs.size();i++) {
            byte[] bv = jvs.getString(i).getBytes("utf-8");
            vs.add(bv);
            pvs.add(ByteString.copyFrom(bv));
        }

        ListBytesOuterClass.ListBytes.Builder builder = ListBytesOuterClass.ListBytes.newBuilder();
        builder.addAllValue(pvs);
        ListBytesOuterClass.ListBytes od = builder.build();
        byte[] pb = od.toByteArray();

        ListBytes ListBytes = new ListBytes();
        ListBytes.value = vs;
        byte[] epb = ProtoBuf.toByteArray(ListBytes);

        assertArrayEquals(pb, epb);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "[\"abcdefghijklmn\",\"中文内容\"]",
            "[\"中文内容\",\"abcdefghijklmn\"]"
    })
    void testDecode(String v) throws InvalidProtocolBufferException, ProtoException, UnsupportedEncodingException {

        List<byte[]> vs = new ArrayList<>();
        List<ByteString> pvs = new ArrayList<>();
        JsonArray jvs = JsonArray.parseArray(v);
        for (int i=0;i<jvs.size();i++) {
            byte[] bv = jvs.getString(i).getBytes("utf-8");
            vs.add(bv);
            pvs.add(ByteString.copyFrom(bv));
        }

        ListBytesOuterClass.ListBytes.Builder builder = ListBytesOuterClass.ListBytes.newBuilder();
        builder.addAllValue(pvs);
        ListBytesOuterClass.ListBytes od = builder.build();
        byte[] pb = od.toByteArray();


        ListBytesOuterClass.ListBytes pbOd = ListBytesOuterClass.ListBytes.parseFrom(pb);

        ListBytes listBytes = ProtoBuf.toObject(pb, ListBytes.class);


        assertEquals(pbOd.getValueList().size(), listBytes.value.size());
        for (int i=0;i<pbOd.getValueList().size();i++) {
            assertArrayEquals(pbOd.getValueList().get(i).toByteArray(), listBytes.value.get(i));
        }

    }

    @ParameterizedTest
    @ValueSource(strings = {
            "[\"abcdefghijklmn\",\"中文内容\"]",
            "[\"中文内容\",\"abcdefghijklmn\"]"
    })
    void testEncodeNoAccess(String v) throws UnsupportedEncodingException, EncodeException, NoSuchFieldException, IllegalAccessException {
        List<byte[]> vs = new ArrayList<>();
        List<ByteString> pvs = new ArrayList<>();
        JsonArray jvs = JsonArray.parseArray(v);
        for (int i=0;i<jvs.size();i++) {
            byte[] bv = jvs.getString(i).getBytes("utf-8");
            vs.add(bv);
            pvs.add(ByteString.copyFrom(bv));
        }

        ListBytesOuterClass.ListBytes.Builder builder = ListBytesOuterClass.ListBytes.newBuilder();
        builder.addAllValue(pvs);
        ListBytesOuterClass.ListBytes od = builder.build();
        byte[] pb = od.toByteArray();

        Field field1F = ClazzUtil.getDeclaredField(ListBytesNoAccess.class, "value");
        field1F.setAccessible(true);

        ListBytesNoAccess ListBytes = new ListBytesNoAccess();
        field1F.set(ListBytes, vs);
        byte[] epb = ProtoBuf.toByteArray(ListBytes);

        assertArrayEquals(pb, epb);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "[\"abcdefghijklmn\",\"中文内容\"]",
            "[\"中文内容\",\"abcdefghijklmn\"]"
    })
    void testDecodeNoAccess(String v) throws InvalidProtocolBufferException, ProtoException, UnsupportedEncodingException, NoSuchFieldException, IllegalAccessException {

        List<byte[]> vs = new ArrayList<>();
        List<ByteString> pvs = new ArrayList<>();
        JsonArray jvs = JsonArray.parseArray(v);
        for (int i=0;i<jvs.size();i++) {
            byte[] bv = jvs.getString(i).getBytes("utf-8");
            vs.add(bv);
            pvs.add(ByteString.copyFrom(bv));
        }

        ListBytesOuterClass.ListBytes.Builder builder = ListBytesOuterClass.ListBytes.newBuilder();
        builder.addAllValue(pvs);
        ListBytesOuterClass.ListBytes od = builder.build();
        byte[] pb = od.toByteArray();


        ListBytesOuterClass.ListBytes pbOd = ListBytesOuterClass.ListBytes.parseFrom(pb);

        ListBytesNoAccess listBytes = ProtoBuf.toObject(pb, ListBytesNoAccess.class);
        Field fieldF = ClazzUtil.getDeclaredField(ListBytesNoAccess.class, "value");
        fieldF.setAccessible(true);

        List<byte[]> values = (List)fieldF.get(listBytes);
        assertEquals(pbOd.getValueList().size(), values.size());
        for (int i=0;i<pbOd.getValueList().size();i++) {
            assertArrayEquals(pbOd.getValueList().get(i).toByteArray(), values.get(i));
        }

    }
}
