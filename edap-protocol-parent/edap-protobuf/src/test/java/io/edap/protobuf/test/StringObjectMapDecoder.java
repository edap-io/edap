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

import io.edap.protobuf.MapDecoder;
import io.edap.protobuf.ProtoBufReader;
import io.edap.protobuf.ProtoException;

import java.util.HashMap;
import java.util.Map;

public class StringObjectMapDecoder implements MapDecoder<String, Object> {

    public Map<String, Object> decode(ProtoBufReader reader) throws ProtoException {
        Map<String, Object> map = new HashMap<>();
        boolean var5 = false;
        String key;
        Object val;
        while (!var5) {
            int var6 = reader.readTag();
            if (var6 == 0) {
                break;
            }
            key = reader.readString();
            reader.readTag();
            val = reader.readObject();
            map.put(key, val);
        }
        return map;
    }
}
