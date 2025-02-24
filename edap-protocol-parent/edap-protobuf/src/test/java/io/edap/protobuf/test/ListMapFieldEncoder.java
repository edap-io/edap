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

package io.edap.protobuf.test;

import io.edap.protobuf.*;
import io.edap.protobuf.model.ProtoBufOption;
import io.edap.protobuf.test.message.ext.ListMapFieldModel;
import io.edap.protobuf.util.ProtoUtil;
import io.edap.protobuf.wire.Field;
import io.edap.util.CollectionUtils;

import java.util.List;
import java.util.Map;

public class ListMapFieldEncoder implements ProtoBufEncoder<ListMapFieldModel> {
    private static final byte[] tag1;

    private static final byte[] mapTag1;

    private static final ProtoBufOption PROTO_BUF_OPTION;

    private MapEntryEncoder<String, Object> mapEntryEncoder;

    static {
        PROTO_BUF_OPTION = new ProtoBufOption();
        tag1 = ProtoUtil.buildFieldData(1, Field.Type.OBJECT, Field.Cardinality.REPEATED);
        mapTag1 = ProtoUtil.buildFieldData(1, Field.Type.MESSAGE, Field.Cardinality.REPEATED);
    }

    public ListMapFieldEncoder() {
    }

    public void encode(ProtoBufWriter var1, ListMapFieldModel var2) throws EncodeException {
        try {
            this.writeList_0(var1, var2.getListMap());
        } catch (Exception var4) {
            throw new EncodeException(var4);
        }
    }

    private void writeList_0(ProtoBufWriter writer, List<Map<String, Object>> vs) throws EncodeException {
        if (CollectionUtils.isEmpty(vs)) {
            return;
        }
        MapEntryEncoder<String, Object> encoder = getMapEntryEncoder();
        for (int i=0;i<vs.size();i++) {
            writer.writeMap(mapTag1, 1, vs.get(i), encoder);
        }
    }

    private MapEntryEncoder<String, Object> getMapEntryEncoder() {
        if (mapEntryEncoder == null) {
            try {
                mapEntryEncoder = ProtoBufCodecRegister.INSTANCE.getMapEntryEncoder(
                        ListMapFieldModel.class.getDeclaredField("").getGenericType(), null, PROTO_BUF_OPTION);
            } catch (Throwable e) {
                throw  new RuntimeException(e.getMessage(), e);
            }
        }
        return mapEntryEncoder;
    }
}
