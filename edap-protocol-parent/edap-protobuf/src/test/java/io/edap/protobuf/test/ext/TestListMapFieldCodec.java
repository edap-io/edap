/*
 * Copyright 2023 The edap Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package io.edap.protobuf.test.ext;

import io.edap.io.BufOut;
import io.edap.protobuf.*;
import io.edap.protobuf.internal.ProtoBufOut;
import io.edap.protobuf.reader.ByteArrayReader;
import io.edap.protobuf.test.message.ext.ListMapFieldModel;
import io.edap.protobuf.writer.StandardProtoBufWriter;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class TestListMapFieldCodec {

    @Test
    public void testEncode() throws EncodeException, ProtoException {
        ProtoBufEncoder<ListMapFieldModel> encoder = ProtoBufCodecRegister.INSTANCE.getEncoder(ListMapFieldModel.class);
        assertNotNull(encoder);

        ListMapFieldModel model = new ListMapFieldModel();

        List<Map<String, Object>> list = new ArrayList<>();
        Map<String, Object> map1 = new HashMap<>();
        map1.put("key1", 1);
        map1.put("key2", "234");
        map1.put("key3", true);
        map1.put("key4", Long.MAX_VALUE);
        list.add(map1);

        Map<String, Object> map2 = new HashMap<>();
        map2.put("k1", new Date());
        map2.put("k2", LocalDateTime.now());
        map2.put("k3", 31415926.7d);
        map2.put("k4", new BigDecimal("1234567.89"));
        map2.put("k5", LocalDate.now());
        list.add(map2);

        model.setListMap(list);

        BufOut out = new ProtoBufOut();
        ProtoBufWriter writer = new StandardProtoBufWriter(out);
        encoder.encode(writer, model);
        byte[] data = writer.toByteArray();
        assertNotNull(data);
        assertTrue(data.length > 0);

        ByteArrayReader reader = new ByteArrayReader(data);
        ProtoBufDecoder<ListMapFieldModel> decoder = ProtoBufCodecRegister.INSTANCE.getDecoder(ListMapFieldModel.class);
        ListMapFieldModel decodeModel = decoder.decode(reader);
        assertNotNull(decodeModel);
    }

}
