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
import io.edap.protobuf.MapEntryEncoder;
import io.edap.protobuf.ProtoBufEncoder;
import io.edap.protobuf.ProtoBufWriter;
import io.edap.protobuf.test.message.v3.Project;
import io.edap.protobuf.util.ProtoUtil;
import io.edap.protobuf.wire.Field;

import java.util.Map;

public class StringObjectMapEncoder implements MapEntryEncoder<String, Project> {

    private static final byte[] tagKey;

    private static final byte[] tagValue;

    private static ProtoBufEncoder<Project> projectEncoder;
    static {
        tagKey = ProtoUtil.buildFieldData(1, Field.Type.STRING, Field.Cardinality.OPTIONAL);
        tagValue = ProtoUtil.buildFieldData(2, Field.Type.OBJECT, Field.Cardinality.OPTIONAL);
    }

    @Override
    public void encode(ProtoBufWriter writer, Map.Entry<String, Project> entry) throws EncodeException {
        writer.writeString(tagKey, entry.getKey());
        writer.writeField(tagValue, entry.getValue());
    }
}
