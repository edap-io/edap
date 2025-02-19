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
import io.edap.protobuf.MapEncoder;
import io.edap.protobuf.ProtoBufWriter;
import io.edap.protobuf.util.ProtoUtil;
import io.edap.protobuf.wire.Field;
import io.edap.util.CollectionUtils;

import java.util.Map;

public class StringObjectMapEncoder implements MapEncoder<String, Object> {

    private static final byte[] tagKey;

    private static final byte[] tagValue;
    static {
        tagKey = ProtoUtil.buildFieldData(1, Field.Type.STRING, Field.Cardinality.OPTIONAL);
        tagValue = ProtoUtil.buildFieldData(2, Field.Type.OBJECT, Field.Cardinality.OPTIONAL);
    }

    @Override
    public void encode(ProtoBufWriter writer, Map<String, Object> map) throws EncodeException {
        if ( CollectionUtils.isEmpty(map)) {
            return;
        }
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            writer.writeString(entry.getKey());
            writer.writeObject(entry.getValue());
        }
    }
}
