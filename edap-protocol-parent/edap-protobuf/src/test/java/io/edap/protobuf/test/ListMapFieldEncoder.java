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
    private static final ProtoBufOption PROTO_BUF_OPTION = new ProtoBufOption();
    private static final byte[] tag1;
    private MapEntryEncoder mapEntryDecoder_c76246d865f02f3afdfdcf953ba6a35e;
    static {
        tag1 = ProtoUtil.buildFieldData(1, Field.Type.OBJECT, Field.Cardinality.REPEATED);
    }

    public ListMapFieldEncoder() {

    }

    public void encode(ProtoBufWriter var1, ListMapFieldModel var2) throws EncodeException {
        try {
            if (!CollectionUtils.isEmpty(var2.getListMap())) {
                MapEntryEncoder var3 = this.mapEntryDecoder_c76246d865f02f3afdfdcf953ba6a35e;
                List var4 = var2.getListMap();

                for(int var5 = 0; var5 < var4.size(); ++var5) {
                    var1.writeMapMessage(tag1, 1, (Map)var4.get(var5), var3);
                }

            }
        } catch (Exception var6) {
            throw new EncodeException(var6);
        }
    }
}
