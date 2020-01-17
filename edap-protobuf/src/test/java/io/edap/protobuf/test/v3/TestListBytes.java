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

import com.alibaba.fastjson.JSONArray;
import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import io.edap.protobuf.ProtoBuf;
import io.edap.protobuf.ProtoBufException;
import io.edap.protobuf.test.message.v3.ListBytes;
import io.edap.protobuf.test.message.v3.ListBytesOuterClass;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.UnsupportedEncodingException;
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
    void testEncode(String v) throws UnsupportedEncodingException {
        List<byte[]> vs = new ArrayList<>();
        List<ByteString> pvs = new ArrayList<>();
        JSONArray jvs = JSONArray.parseArray(v);
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
    void testDecode(String v) throws InvalidProtocolBufferException, ProtoBufException, UnsupportedEncodingException {

        List<byte[]> vs = new ArrayList<>();
        List<ByteString> pvs = new ArrayList<>();
        JSONArray jvs = JSONArray.parseArray(v);
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
}
