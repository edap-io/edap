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

import com.google.protobuf.InvalidProtocolBufferException;
import io.edap.json.Eson;
import io.edap.json.JsonObject;
import io.edap.protobuf.EncodeException;
import io.edap.protobuf.ProtoBuf;
import io.edap.protobuf.ProtoBufException;
import io.edap.protobuf.test.message.v3.OneMap;
import io.edap.protobuf.test.message.v3.OneMapNoAccess;
import io.edap.protobuf.test.message.v3.OneMapOuterClass;
import io.edap.protobuf.test.message.v3.Project;
import io.edap.util.ClazzUtil;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;

import static io.edap.protobuf.test.TestUtil.conver2HexStr;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * @author : luysh@yonyou.com
 * @date : 2020/1/6
 */
public class TestOneMap {

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"edap\":{\"id\":1,\"name\":\"edap\",\"repoPath\":\"https://www.easyea.com/edap/edap.git\"}}"
    })
    void testEncode(String v) throws EncodeException {
        Map<String, OneMapOuterClass.Project> pbValue = new HashMap<>();
        JsonObject jvalue = Eson.parseJsonObject(v);
        Map<String, Project> epbValue = new HashMap<>();
        for (Map.Entry<String, Object> jitem : jvalue.entrySet()) {
            JsonObject jv = (JsonObject)jitem.getValue();
            OneMapOuterClass.Project.Builder vbuilder = OneMapOuterClass.Project.newBuilder();
            vbuilder.setId(jv.getLongValue("id"));
            vbuilder.setName(jv.getString("name"));
            vbuilder.setRepoPath(jv.getString("repoPath"));
            pbValue.put(jitem.getKey(), vbuilder.build());

            Project proj = new Project();
            proj.setId(jv.getLongValue("id"));
            proj.setName(jv.getString("name"));
            proj.setRepoPath(jv.getString("repoPath"));
            epbValue.put(jitem.getKey(), proj);
        }
        OneMapOuterClass.OneMap.Builder builder = OneMapOuterClass.OneMap.newBuilder();
        builder.putAllValue(pbValue);
        OneMapOuterClass.OneMap oi32 = builder.build();
        byte[] pb = oi32.toByteArray();


        System.out.println("+--------------------+");
        System.out.println(conver2HexStr(pb));
        System.out.println("+--------------------+");
        OneMap OneMap = new OneMap();
        OneMap.setValue(epbValue);
        byte[] epb = ProtoBuf.toByteArray(OneMap);


        assertArrayEquals(pb, epb);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"edap\":{\"id\":1,\"name\":\"edap\",\"repoPath\":\"https://www.easyea.com/edap/edap.git\"}}"
    })
    void testDecode(String v) throws InvalidProtocolBufferException, ProtoBufException {
        Map<String, OneMapOuterClass.Project> pbValue = new HashMap<>();
        OneMapOuterClass.OneMap.Builder builder = OneMapOuterClass.OneMap.newBuilder();
        JsonObject jvalue = Eson.parseJsonObject(v);
        for (Map.Entry<String, Object> jitem : jvalue.entrySet()) {
            JsonObject jv = (JsonObject) jitem.getValue();
            OneMapOuterClass.Project.Builder vbuilder = OneMapOuterClass.Project.newBuilder();
            vbuilder.setId(jv.getLongValue("id"));
            vbuilder.setName(jv.getString("name"));
            vbuilder.setRepoPath(jv.getString("repoPath"));
            pbValue.put(jitem.getKey(), vbuilder.build());
        }
        builder.putAllValue(pbValue);
        OneMapOuterClass.OneMap oSfixed64 = builder.build();
        byte[] pb = oSfixed64.toByteArray();


        OneMapOuterClass.OneMap pbOf = OneMapOuterClass.OneMap.parseFrom(pb);

        OneMap OneMap = ProtoBuf.toObject(pb, OneMap.class);


        assertEquals(pbOf.getValueMap().size(), OneMap.getValue().size());

    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"edap\":{\"id\":1,\"name\":\"edap\",\"repoPath\":\"https://www.easyea.com/edap/edap.git\"}}"
    })
    void testEncodeNoAccess(String v) throws EncodeException, NoSuchFieldException, IllegalAccessException {
        Map<String, OneMapOuterClass.Project> pbValue = new HashMap<>();
        JsonObject jvalue = Eson.parseJsonObject(v);
        Map<String, Project> epbValue = new HashMap<>();
        for (Map.Entry<String, Object> jitem : jvalue.entrySet()) {
            JsonObject jv = (JsonObject)jitem.getValue();
            OneMapOuterClass.Project.Builder vbuilder = OneMapOuterClass.Project.newBuilder();
            vbuilder.setId(jv.getLongValue("id"));
            vbuilder.setName(jv.getString("name"));
            vbuilder.setRepoPath(jv.getString("repoPath"));
            pbValue.put(jitem.getKey(), vbuilder.build());

            Project proj = new Project();
            proj.setId(jv.getLongValue("id"));
            proj.setName(jv.getString("name"));
            proj.setRepoPath(jv.getString("repoPath"));
            epbValue.put(jitem.getKey(), proj);
        }
        OneMapOuterClass.OneMap.Builder builder = OneMapOuterClass.OneMap.newBuilder();
        builder.putAllValue(pbValue);
        OneMapOuterClass.OneMap oi32 = builder.build();
        byte[] pb = oi32.toByteArray();


        System.out.println("+--------------------+");
        System.out.println(conver2HexStr(pb));
        System.out.println("+--------------------+");

        Field fieldF = ClazzUtil.getDeclaredField(OneMapNoAccess.class, "value");
        fieldF.setAccessible(true);

        OneMapNoAccess oneMap = new OneMapNoAccess();
        fieldF.set(oneMap, epbValue);
        byte[] epb = ProtoBuf.toByteArray(oneMap);


        assertArrayEquals(pb, epb);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"edap\":{\"id\":1,\"name\":\"edap\",\"repoPath\":\"https://www.easyea.com/edap/edap.git\"}}"
    })
    void testDecodeNoAccess(String v) throws InvalidProtocolBufferException, ProtoBufException, NoSuchFieldException, IllegalAccessException {
        Map<String, OneMapOuterClass.Project> pbValue = new HashMap<>();
        OneMapOuterClass.OneMap.Builder builder = OneMapOuterClass.OneMap.newBuilder();
        JsonObject jvalue = Eson.parseJsonObject(v);
        for (Map.Entry<String, Object> jitem : jvalue.entrySet()) {
            JsonObject jv = (JsonObject) jitem.getValue();
            OneMapOuterClass.Project.Builder vbuilder = OneMapOuterClass.Project.newBuilder();
            vbuilder.setId(jv.getLongValue("id"));
            vbuilder.setName(jv.getString("name"));
            vbuilder.setRepoPath(jv.getString("repoPath"));
            pbValue.put(jitem.getKey(), vbuilder.build());
        }
        builder.putAllValue(pbValue);
        OneMapOuterClass.OneMap oSfixed64 = builder.build();
        byte[] pb = oSfixed64.toByteArray();


        OneMapOuterClass.OneMap pbOf = OneMapOuterClass.OneMap.parseFrom(pb);

        OneMapNoAccess oneMap = ProtoBuf.toObject(pb, OneMapNoAccess.class);
        Field fieldF = ClazzUtil.getDeclaredField(OneMapNoAccess.class, "value");
        fieldF.setAccessible(true);

        assertEquals(pbOf.getValueMap().size(), ((Map)fieldF.get(oneMap)).size());

    }
}
