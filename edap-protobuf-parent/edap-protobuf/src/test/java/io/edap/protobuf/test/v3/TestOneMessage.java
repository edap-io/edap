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
import io.edap.protobuf.model.ProtoBufOption;
import io.edap.protobuf.test.message.v3.OneMessage;
import io.edap.protobuf.test.message.v3.OneMessageNoAccess;
import io.edap.protobuf.test.message.v3.OneMessageOuterClass;
import io.edap.protobuf.test.message.v3.Proj;
import io.edap.util.ClazzUtil;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.Field;

import static io.edap.protobuf.test.TestUtil.conver2HexStr;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * @author : louis@easyea.com
 * @date : 2020/1/6
 */
public class TestOneMessage {

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"id\":1,\"name\":\"edap\",\"repoPath\":\"https://www.easyea.com/edap/edap.git\"}"
    })
    void testEncode(String v) throws EncodeException {
        JsonObject jvalue = Eson.parseJsonObject(v);

        OneMessageOuterClass.Proj.Builder pbuilder = OneMessageOuterClass.Proj.newBuilder();
        pbuilder.setId(jvalue.getLongValue("id"));
        pbuilder.setName(jvalue.getString("name"));
        pbuilder.setRepoPath(jvalue.getString("repoPath"));
        OneMessageOuterClass.OneMessage.Builder builder = OneMessageOuterClass.OneMessage.newBuilder();
        builder.setValue(pbuilder.build());
        OneMessageOuterClass.OneMessage oi32 = builder.build();
        byte[] pb = oi32.toByteArray();


        System.out.println("+-[" + pb.length + "]-------------------+");
        System.out.println(conver2HexStr(pb));
        System.out.println("+--------------------+");
        Proj proj = new Proj();
        proj.setId(jvalue.getLongValue("id"));
        proj.setName(jvalue.getString("name"));
        proj.setRepoPath(jvalue.getString("repoPath"));
        OneMessage OneMessage = new OneMessage();
        OneMessage.setProj(proj);
        byte[] epb = ProtoBuf.toByteArray(OneMessage);
        System.out.println("+-epb[" + pb.length + "]-------------------+");
        System.out.println(conver2HexStr(pb));
        System.out.println("+--------------------+");
        assertArrayEquals(pb, epb);

        ProtoBufOption option = new ProtoBufOption();
        option.setCodecType(ProtoBuf.CodecType.FAST);
        epb = ProtoBuf.toByteArray(OneMessage, option);
        System.out.println("+-fepb[" + epb.length + "]-------------------+");
        System.out.println(conver2HexStr(epb));
        System.out.println("+--------------------+");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"id\":1,\"name\":\"edap\",\"repoPath\":\"https://www.easyea.com/edap/edap.git\"}"
    })
    void testDecode(String v) throws InvalidProtocolBufferException, ProtoBufException {
        JsonObject jvalue = Eson.parseJsonObject(v);

        OneMessageOuterClass.Proj.Builder pbuilder = OneMessageOuterClass.Proj.newBuilder();
        pbuilder.setId(jvalue.getLongValue("id"));
        pbuilder.setName(jvalue.getString("name"));
        pbuilder.setRepoPath(jvalue.getString("repoPath"));
        OneMessageOuterClass.OneMessage.Builder builder = OneMessageOuterClass.OneMessage.newBuilder();
        builder.setValue(pbuilder.build());
        OneMessageOuterClass.OneMessage oi32 = builder.build();
        byte[] pb = oi32.toByteArray();


        OneMessageOuterClass.OneMessage pbOf = OneMessageOuterClass.OneMessage.parseFrom(pb);

        OneMessage oneMessage = ProtoBuf.toObject(pb, OneMessage.class);

        assertEquals(pbOf.getValue().getId(), oneMessage.getProj().getId());
        assertEquals(pbOf.getValue().getName(), oneMessage.getProj().getName());
        assertEquals(pbOf.getValue().getRepoPath(), oneMessage.getProj().getRepoPath());


        ProtoBufOption option = new ProtoBufOption();
        option.setCodecType(ProtoBuf.CodecType.FAST);
        byte[] epb = ProtoBuf.toByteArray(oneMessage, option);
        System.out.println("+-fepb[" + epb.length + "]-------------------+");
        System.out.println(conver2HexStr(epb));
        System.out.println("+--------------------+");
        oneMessage = ProtoBuf.toObject(epb, OneMessage.class, option);

        assertEquals(pbOf.getValue().getId(), oneMessage.getProj().getId());
        assertEquals(pbOf.getValue().getName(), oneMessage.getProj().getName());
        assertEquals(pbOf.getValue().getRepoPath(), oneMessage.getProj().getRepoPath());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"id\":1,\"name\":\"edap\",\"repoPath\":\"https://www.easyea.com/edap/edap.git\"}"
    })
    void testEncodeNoAccess(String v) throws EncodeException, NoSuchFieldException, IllegalAccessException {
        JsonObject jvalue = Eson.parseJsonObject(v);

        OneMessageOuterClass.Proj.Builder pbuilder = OneMessageOuterClass.Proj.newBuilder();
        pbuilder.setId(jvalue.getLongValue("id"));
        pbuilder.setName(jvalue.getString("name"));
        pbuilder.setRepoPath(jvalue.getString("repoPath"));
        OneMessageOuterClass.OneMessage.Builder builder = OneMessageOuterClass.OneMessage.newBuilder();
        builder.setValue(pbuilder.build());
        OneMessageOuterClass.OneMessage oi32 = builder.build();
        byte[] pb = oi32.toByteArray();


        System.out.println("+--------------------+");
        System.out.println(conver2HexStr(pb));
        System.out.println("+--------------------+");
        Proj proj = new Proj();
        proj.setId(jvalue.getLongValue("id"));
        proj.setName(jvalue.getString("name"));
        proj.setRepoPath(jvalue.getString("repoPath"));

        Field fieldF = ClazzUtil.getDeclaredField(OneMessageNoAccess.class, "proj");
        fieldF.setAccessible(true);

        OneMessageNoAccess oneMessage = new OneMessageNoAccess();
        fieldF.set(oneMessage, proj);
        byte[] epb = ProtoBuf.toByteArray(oneMessage);


        assertArrayEquals(pb, epb);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"id\":1,\"name\":\"edap\",\"repoPath\":\"https://www.easyea.com/edap/edap.git\"}"
    })
    void testDecodeNoAccess(String v) throws InvalidProtocolBufferException, ProtoBufException, NoSuchFieldException, IllegalAccessException {
        JsonObject jvalue = Eson.parseJsonObject(v);

        OneMessageOuterClass.Proj.Builder pbuilder = OneMessageOuterClass.Proj.newBuilder();
        pbuilder.setId(jvalue.getLongValue("id"));
        pbuilder.setName(jvalue.getString("name"));
        pbuilder.setRepoPath(jvalue.getString("repoPath"));
        OneMessageOuterClass.OneMessage.Builder builder = OneMessageOuterClass.OneMessage.newBuilder();
        builder.setValue(pbuilder.build());
        OneMessageOuterClass.OneMessage oi32 = builder.build();
        byte[] pb = oi32.toByteArray();


        OneMessageOuterClass.OneMessage pbOf = OneMessageOuterClass.OneMessage.parseFrom(pb);

        OneMessageNoAccess OneMessage = ProtoBuf.toObject(pb, OneMessageNoAccess.class);

        Field fieldF = ClazzUtil.getDeclaredField(OneMessageNoAccess.class, "proj");
        fieldF.setAccessible(true);

        Proj proj = (Proj)fieldF.get(OneMessage);
        assertEquals(pbOf.getValue().getId(), proj.getId());
        assertEquals(pbOf.getValue().getName(), proj.getName());
        assertEquals(pbOf.getValue().getRepoPath(), proj.getRepoPath());

        ProtoBufOption option = new ProtoBufOption();
        option.setCodecType(ProtoBuf.CodecType.FAST);
        byte[] epb = ProtoBuf.toByteArray(OneMessage, option);
        System.out.println("+-fepb[" + epb.length + "]-------------------+");
        System.out.println(conver2HexStr(epb));
        System.out.println("+--------------------+");
        OneMessageNoAccess oneMessage = ProtoBuf.toObject(epb, OneMessageNoAccess.class, option);
        proj = (Proj)fieldF.get(OneMessage);
        assertEquals(pbOf.getValue().getId(), proj.getId());
        assertEquals(pbOf.getValue().getName(), proj.getName());
        assertEquals(pbOf.getValue().getRepoPath(), proj.getRepoPath());
    }
}
