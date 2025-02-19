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

import io.edap.protobuf.EncodeException;
import io.edap.protobuf.ProtoBufEncoder;
import io.edap.protobuf.ProtoBufWriter;
import io.edap.protobuf.test.message.ext.ListMapFieldModel;
import io.edap.protobuf.util.ProtoUtil;
import io.edap.protobuf.wire.Field;
import io.edap.util.CollectionUtils;

import java.util.List;
import java.util.Map;

public class ListMapFieldEncoder implements ProtoBufEncoder<ListMapFieldModel> {
    private static final byte[] tag1;

    private static final byte[] mapTag1;
    static {
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

    private void writeList_0(ProtoBufWriter param1, List<Map<String, Object>> param2) throws EncodeException {
        if (CollectionUtils.isEmpty(param2)) {
            return;
        }
        for (int i=0;i<param2.size();i++) {

        }
    }

    private void writeMap_0(ProtoBufWriter param1, Map<String, Object> map) {
        if (CollectionUtils.isEmpty(map)) {
            return;
        }

//        for (Map.Entry<String, Object> entry : map.entrySet()) {
//            param1.write
//        }
    }
}
