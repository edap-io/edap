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
import io.edap.json.JsonArray;
import io.edap.json.JsonObject;
import io.edap.protobuf.EncodeException;
import io.edap.protobuf.ProtoBuf;
import io.edap.protobuf.ProtoException;
import io.edap.protobuf.model.ProtoBufOption;
import io.edap.protobuf.test.message.v3.*;
import io.edap.util.ClazzUtil;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import static io.edap.protobuf.test.TestUtil.conver2HexStr;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;


public class TestListMessage {

    @ParameterizedTest
    @ValueSource(strings = {
            "[{\"id\":1,\"name\":\"edap\",\"repoPath\":\"https://www.easyea.com/edap/edap.git\"}," +
                    "{\"id\":2,\"name\":\"edao\",\"repoPath\":\"https://www.easyea.com/easyea/edao.git\"}]"
    })
    void testEncode(String v) throws EncodeException {

        JsonArray jvs = JsonArray.parseArray(v);
        ListMessageOuterClass.ListMessage.Builder builder = ListMessageOuterClass.ListMessage.newBuilder();

        ListMessage listMessage = new ListMessage();
        List<Proj> ps = new ArrayList<>();
        for (int i=0;i<jvs.size();i++) {
            JsonObject jvalue = jvs.getJsonObject(i);

            OneMessageOuterClass.Proj.Builder pbuilder = OneMessageOuterClass.Proj.newBuilder();
            pbuilder.setId(jvalue.getLongValue("id"));
            pbuilder.setName(jvalue.getString("name"));
            pbuilder.setRepoPath(jvalue.getString("repoPath"));
            builder.addValue(pbuilder.build());


            Proj proj = new Proj();
            proj.setId(jvalue.getLongValue("id"));
            proj.setName(jvalue.getString("name"));
            proj.setRepoPath(jvalue.getString("repoPath"));
            ps.add(proj);
        }

        ListMessageOuterClass.ListMessage oi32 = builder.build();
        byte[] pb = oi32.toByteArray();


        System.out.println("+--------------------+");
        System.out.println(conver2HexStr(pb));
        System.out.println("+--------------------+");


        listMessage.list = ps;
        byte[] epb = ProtoBuf.toByteArray(listMessage);


        assertArrayEquals(pb, epb);

        ProtoBufOption option = new ProtoBufOption();
        option.setCodecType(ProtoBuf.CodecType.FAST);
        epb = ProtoBuf.toByteArray(listMessage, option);
        System.out.println("+-fepb[" + epb.length + "]-------------------+");
        System.out.println(conver2HexStr(epb));
        System.out.println("+--------------------+");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "[{\"id\":1,\"name\":\"edap\",\"repoPath\":\"https://www.easyea.com/edap/edap.git\"}," +
                    "{\"id\":2,\"name\":\"edao\",\"repoPath\":\"https://www.easyea.com/easyea/edao.git\"}]",
            "[{\"id\":1,\"name\":\"edap\",\"repoPath\":\"https://www.easyea.com/edap/edap.git\"}]"
    })
    void testDecode(String v) throws InvalidProtocolBufferException, ProtoException {
        JsonArray jvs = JsonArray.parseArray(v);
        ListMessageOuterClass.ListMessage.Builder builder = ListMessageOuterClass.ListMessage.newBuilder();

        ListMessage listMessage = new ListMessage();
        List<Proj> ps = new ArrayList<>();
        for (int i=0;i<jvs.size();i++) {
            JsonObject jvalue = jvs.getJsonObject(i);

            OneMessageOuterClass.Proj.Builder pbuilder = OneMessageOuterClass.Proj.newBuilder();
            pbuilder.setId(jvalue.getLongValue("id"));
            pbuilder.setName(jvalue.getString("name"));
            pbuilder.setRepoPath(jvalue.getString("repoPath"));
            builder.addValue(pbuilder.build());


            Proj proj = new Proj();
            proj.setId(jvalue.getLongValue("id"));
            proj.setName(jvalue.getString("name"));
            proj.setRepoPath(jvalue.getString("repoPath"));
            ps.add(proj);
        }

        ListMessageOuterClass.ListMessage oi32 = builder.build();
        byte[] pb = oi32.toByteArray();


        ListMessageOuterClass.ListMessage pbOfs = ListMessageOuterClass.ListMessage.parseFrom(pb);

        ListMessage listMessage1s = ProtoBuf.toObject(pb, ListMessage.class);

        assertEquals(listMessage1s.list.size(), pbOfs.getValueList().size());
        for (int i=0;i<listMessage1s.list.size();i++) {
            OneMessageOuterClass.Proj pbOf = pbOfs.getValueList().get(i);
            Proj proj = listMessage1s.list.get(i);
            assertEquals(pbOf.getId(), proj.getId());
            assertEquals(pbOf.getName(), proj.getName());
            assertEquals(pbOf.getRepoPath(), proj.getRepoPath());
        }

        ProtoBufOption option = new ProtoBufOption();
        option.setCodecType(ProtoBuf.CodecType.FAST);
        byte[] epb = ProtoBuf.toByteArray(listMessage1s, option);
        System.out.println("+-fepb[" + epb.length + "]-------------------+");
        System.out.println(conver2HexStr(epb));
        System.out.println("+--------------------+");

        listMessage1s = ProtoBuf.toObject(epb, ListMessage.class, option);
        assertEquals(listMessage1s.list.size(), pbOfs.getValueList().size());
        for (int i=0;i<listMessage1s.list.size();i++) {
            OneMessageOuterClass.Proj pbOf = pbOfs.getValueList().get(i);
            Proj proj = listMessage1s.list.get(i);
            assertEquals(pbOf.getId(), proj.getId());
            assertEquals(pbOf.getName(), proj.getName());
            assertEquals(pbOf.getRepoPath(), proj.getRepoPath());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "[{\"id\":1,\"name\":\"edap\",\"repoPath\":\"https://www.easyea.com/edap/edap.git\"}," +
                    "{\"id\":2,\"name\":\"edao\",\"repoPath\":\"https://www.easyea.com/easyea/edao.git\"}]"
    })
    void testEncodeArray(String v) throws EncodeException {

        JsonArray jvs = JsonArray.parseArray(v);
        ListMessageOuterClass.ListMessage.Builder builder = ListMessageOuterClass.ListMessage.newBuilder();

        ArrayMessage listMessage = new ArrayMessage();
        Proj[] ps = new Proj[jvs.size()];
        for (int i=0;i<jvs.size();i++) {
            JsonObject jvalue = jvs.getJsonObject(i);

            OneMessageOuterClass.Proj.Builder pbuilder = OneMessageOuterClass.Proj.newBuilder();
            pbuilder.setId(jvalue.getLongValue("id"));
            pbuilder.setName(jvalue.getString("name"));
            pbuilder.setRepoPath(jvalue.getString("repoPath"));
            builder.addValue(pbuilder.build());


            Proj proj = new Proj();
            proj.setId(jvalue.getLongValue("id"));
            proj.setName(jvalue.getString("name"));
            proj.setRepoPath(jvalue.getString("repoPath"));
            ps[i] = proj;
        }

        ListMessageOuterClass.ListMessage oi32 = builder.build();
        byte[] pb = oi32.toByteArray();


        System.out.println("+--------------------+");
        System.out.println(conver2HexStr(pb));
        System.out.println("+--------------------+");


        listMessage.list = ps;
        byte[] epb = ProtoBuf.toByteArray(listMessage);
        System.out.println(conver2HexStr(epb));
        System.out.println("+--------------------+");

        assertArrayEquals(pb, epb);

        ProtoBufOption option = new ProtoBufOption();
        option.setCodecType(ProtoBuf.CodecType.FAST);
        epb = ProtoBuf.toByteArray(listMessage, option);
        System.out.println("+-fepb[" + epb.length + "]-------------------+");
        System.out.println(conver2HexStr(epb));
        System.out.println("+--------------------+");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "[{\"id\":1,\"name\":\"edap\",\"repoPath\":\"https://www.easyea.com/edap/edap.git\"}," +
                    "{\"id\":2,\"name\":\"edao\",\"repoPath\":\"https://www.easyea.com/easyea/edao.git\"}]"
    })
    void testDecodeArray(String v) throws InvalidProtocolBufferException, ProtoException {
        JsonArray jvs = JsonArray.parseArray(v);
        ListMessageOuterClass.ListMessage.Builder builder = ListMessageOuterClass.ListMessage.newBuilder();

        for (int i=0;i<jvs.size();i++) {
            JsonObject jvalue = jvs.getJsonObject(i);

            OneMessageOuterClass.Proj.Builder pbuilder = OneMessageOuterClass.Proj.newBuilder();
            pbuilder.setId(jvalue.getLongValue("id"));
            pbuilder.setName(jvalue.getString("name"));
            pbuilder.setRepoPath(jvalue.getString("repoPath"));
            builder.addValue(pbuilder.build());
        }

        ListMessageOuterClass.ListMessage oi32 = builder.build();
        byte[] pb = oi32.toByteArray();


        ListMessageOuterClass.ListMessage pbOfs = ListMessageOuterClass.ListMessage.parseFrom(pb);

        ArrayMessage listMessage1s = ProtoBuf.toObject(pb, ArrayMessage.class);

        assertEquals(listMessage1s.list.length, pbOfs.getValueList().size());
        for (int i=0;i<listMessage1s.list.length;i++) {
            OneMessageOuterClass.Proj pbOf = pbOfs.getValueList().get(i);
            Proj proj = listMessage1s.list[i];
            assertEquals(pbOf.getId(), proj.getId());
            assertEquals(pbOf.getName(), proj.getName());
            assertEquals(pbOf.getRepoPath(), proj.getRepoPath());
        }

        ProtoBufOption option = new ProtoBufOption();
        option.setCodecType(ProtoBuf.CodecType.FAST);
        byte[] epb = ProtoBuf.toByteArray(listMessage1s, option);
        System.out.println("+-fepb[" + epb.length + "]-------------------+");
        System.out.println(conver2HexStr(epb));
        System.out.println("+--------------------+");

        listMessage1s = ProtoBuf.toObject(epb, ArrayMessage.class, option);

        assertEquals(listMessage1s.list.length, pbOfs.getValueList().size());
        for (int i=0;i<listMessage1s.list.length;i++) {
            OneMessageOuterClass.Proj pbOf = pbOfs.getValueList().get(i);
            Proj proj = listMessage1s.list[i];
            assertEquals(pbOf.getId(), proj.getId());
            assertEquals(pbOf.getName(), proj.getName());
            assertEquals(pbOf.getRepoPath(), proj.getRepoPath());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "[{\"id\":1,\"name\":\"edap\",\"repoPath\":\"https://www.easyea.com/edap/edap.git\"}," +
                    "{\"id\":2,\"name\":\"edao\",\"repoPath\":\"https://www.easyea.com/easyea/edao.git\"}]"
    })
    void testEncodeNoAccess(String v) throws EncodeException, NoSuchFieldException, IllegalAccessException {

        JsonArray jvs = JsonArray.parseArray(v);
        ListMessageOuterClass.ListMessage.Builder builder = ListMessageOuterClass.ListMessage.newBuilder();

        ListMessageNoAccess listMessage = new ListMessageNoAccess();
        List<Proj> ps = new ArrayList<>();
        for (int i=0;i<jvs.size();i++) {
            JsonObject jvalue = jvs.getJsonObject(i);

            OneMessageOuterClass.Proj.Builder pbuilder = OneMessageOuterClass.Proj.newBuilder();
            pbuilder.setId(jvalue.getLongValue("id"));
            pbuilder.setName(jvalue.getString("name"));
            pbuilder.setRepoPath(jvalue.getString("repoPath"));
            builder.addValue(pbuilder.build());


            Proj proj = new Proj();
            proj.setId(jvalue.getLongValue("id"));
            proj.setName(jvalue.getString("name"));
            proj.setRepoPath(jvalue.getString("repoPath"));
            ps.add(proj);
        }

        ListMessageOuterClass.ListMessage oi32 = builder.build();
        byte[] pb = oi32.toByteArray();


        System.out.println("+--------------------+");
        System.out.println(conver2HexStr(pb));
        System.out.println("+--------------------+");

        Field field1F = ClazzUtil.getDeclaredField(ListMessageNoAccess.class, "list");
        field1F.setAccessible(true);

        field1F.set(listMessage, ps);
        byte[] epb = ProtoBuf.toByteArray(listMessage);


        assertArrayEquals(pb, epb);

        ProtoBufOption option = new ProtoBufOption();
        option.setCodecType(ProtoBuf.CodecType.FAST);
        epb = ProtoBuf.toByteArray(listMessage, option);
        System.out.println("+-fepb[" + epb.length + "]-------------------+");
        System.out.println(conver2HexStr(epb));
        System.out.println("+--------------------+");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "[{\"id\":1,\"name\":\"edap\",\"repoPath\":\"https://www.easyea.com/edap/edap.git\"}," +
                    "{\"id\":2,\"name\":\"edao\",\"repoPath\":\"https://www.easyea.com/easyea/edao.git\"}]"
    })
    void testEncodeArrayNoAccess(String v) throws EncodeException, NoSuchFieldException, IllegalAccessException {

        JsonArray jvs = JsonArray.parseArray(v);
        ListMessageOuterClass.ListMessage.Builder builder = ListMessageOuterClass.ListMessage.newBuilder();

        ArrayMessageNoAccess listMessage = new ArrayMessageNoAccess();
        Proj[] ps = new Proj[jvs.size()];
        for (int i=0;i<jvs.size();i++) {
            JsonObject jvalue = jvs.getJsonObject(i);

            OneMessageOuterClass.Proj.Builder pbuilder = OneMessageOuterClass.Proj.newBuilder();
            pbuilder.setId(jvalue.getLongValue("id"));
            pbuilder.setName(jvalue.getString("name"));
            pbuilder.setRepoPath(jvalue.getString("repoPath"));
            builder.addValue(pbuilder.build());


            Proj proj = new Proj();
            proj.setId(jvalue.getLongValue("id"));
            proj.setName(jvalue.getString("name"));
            proj.setRepoPath(jvalue.getString("repoPath"));
            ps[i] = proj;
        }

        ListMessageOuterClass.ListMessage oi32 = builder.build();
        byte[] pb = oi32.toByteArray();


        System.out.println("+-fepb[" + pb.length + "]-------------------+");
        System.out.println(conver2HexStr(pb));
        System.out.println("+--------------------+");

        Field field1F = ClazzUtil.getDeclaredField(ArrayMessageNoAccess.class, "list");
        field1F.setAccessible(true);

        field1F.set(listMessage, ps);
        byte[] epb = ProtoBuf.toByteArray(listMessage);
        System.out.println(conver2HexStr(epb));
        System.out.println("+--------------------+");

        assertArrayEquals(pb, epb);

        ProtoBufOption option = new ProtoBufOption();
        option.setCodecType(ProtoBuf.CodecType.FAST);
        epb = ProtoBuf.toByteArray(listMessage, option);
        System.out.println("+-fepb[" + epb.length + "]-------------------+");
        System.out.println(conver2HexStr(epb));
        System.out.println("+--------------------+");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "[{\"id\":1,\"name\":\"edap\",\"repoPath\":\"https://www.easyea.com/edap/edap.git\"}," +
                    "{\"id\":2,\"name\":\"edao\",\"repoPath\":\"https://www.easyea.com/easyea/edao.git\"}]"
    })
    void testDecodeNoAccess(String v) throws InvalidProtocolBufferException, ProtoException, NoSuchFieldException, IllegalAccessException {
        JsonArray jvs = JsonArray.parseArray(v);
        ListMessageOuterClass.ListMessage.Builder builder = ListMessageOuterClass.ListMessage.newBuilder();

        ListMessage listMessage = new ListMessage();
        List<Proj> ps = new ArrayList<>();
        for (int i=0;i<jvs.size();i++) {
            JsonObject jvalue = jvs.getJsonObject(i);

            OneMessageOuterClass.Proj.Builder pbuilder = OneMessageOuterClass.Proj.newBuilder();
            pbuilder.setId(jvalue.getLongValue("id"));
            pbuilder.setName(jvalue.getString("name"));
            pbuilder.setRepoPath(jvalue.getString("repoPath"));
            builder.addValue(pbuilder.build());


            Proj proj = new Proj();
            proj.setId(jvalue.getLongValue("id"));
            proj.setName(jvalue.getString("name"));
            proj.setRepoPath(jvalue.getString("repoPath"));
            ps.add(proj);
        }

        ListMessageOuterClass.ListMessage oi32 = builder.build();
        byte[] pb = oi32.toByteArray();


        ListMessageOuterClass.ListMessage pbOfs = ListMessageOuterClass.ListMessage.parseFrom(pb);

        ListMessageNoAccess listMessage1s = ProtoBuf.toObject(pb, ListMessageNoAccess.class);
        Field fieldF = ClazzUtil.getDeclaredField(ListMessageNoAccess.class, "list");
        fieldF.setAccessible(true);
        List<Proj> list = (List<Proj>)fieldF.get(listMessage1s);

        assertEquals(list.size(), pbOfs.getValueList().size());
        for (int i=0;i<list.size();i++) {
            OneMessageOuterClass.Proj pbOf = pbOfs.getValueList().get(i);
            Proj proj = list.get(i);
            assertEquals(pbOf.getId(), proj.getId());
            assertEquals(pbOf.getName(), proj.getName());
            assertEquals(pbOf.getRepoPath(), proj.getRepoPath());
        }

        ProtoBufOption option = new ProtoBufOption();
        option.setCodecType(ProtoBuf.CodecType.FAST);
        byte[] epb = ProtoBuf.toByteArray(listMessage1s, option);
        System.out.println("+-fepb[" + epb.length + "]-------------------+");
        System.out.println(conver2HexStr(epb));
        System.out.println("+--------------------+");

        listMessage1s = ProtoBuf.toObject(epb, ListMessageNoAccess.class, option);
        list = (List<Proj>)fieldF.get(listMessage1s);
        assertEquals(list.size(), pbOfs.getValueList().size());
        for (int i=0;i<list.size();i++) {
            OneMessageOuterClass.Proj pbOf = pbOfs.getValueList().get(i);
            Proj proj = list.get(i);
            assertEquals(pbOf.getId(), proj.getId());
            assertEquals(pbOf.getName(), proj.getName());
            assertEquals(pbOf.getRepoPath(), proj.getRepoPath());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "[{\"id\":1,\"name\":\"edap\",\"repoPath\":\"https://www.easyea.com/edap/edap.git\"}," +
                    "{\"id\":2,\"name\":\"edao\",\"repoPath\":\"https://www.easyea.com/easyea/edao.git\"}]"
    })
    void testDecodeArrayNoAccess(String v) throws InvalidProtocolBufferException, ProtoException, NoSuchFieldException, IllegalAccessException {
        JsonArray jvs = JsonArray.parseArray(v);
        ListMessageOuterClass.ListMessage.Builder builder = ListMessageOuterClass.ListMessage.newBuilder();

        for (int i=0;i<jvs.size();i++) {
            JsonObject jvalue = jvs.getJsonObject(i);

            OneMessageOuterClass.Proj.Builder pbuilder = OneMessageOuterClass.Proj.newBuilder();
            pbuilder.setId(jvalue.getLongValue("id"));
            pbuilder.setName(jvalue.getString("name"));
            pbuilder.setRepoPath(jvalue.getString("repoPath"));
            builder.addValue(pbuilder.build());
        }

        ListMessageOuterClass.ListMessage oi32 = builder.build();
        byte[] pb = oi32.toByteArray();


        ListMessageOuterClass.ListMessage pbOfs = ListMessageOuterClass.ListMessage.parseFrom(pb);

        ArrayMessageNoAccess listMessage1s = ProtoBuf.toObject(pb, ArrayMessageNoAccess.class);
        Field fieldF = ClazzUtil.getDeclaredField(ArrayMessageNoAccess.class, "list");
        fieldF.setAccessible(true);

        Proj[] list = (Proj[])fieldF.get(listMessage1s);
        assertEquals(list.length, pbOfs.getValueList().size());
        for (int i=0;i<list.length;i++) {
            OneMessageOuterClass.Proj pbOf = pbOfs.getValueList().get(i);
            Proj proj = list[i];
            assertEquals(pbOf.getId(), proj.getId());
            assertEquals(pbOf.getName(), proj.getName());
            assertEquals(pbOf.getRepoPath(), proj.getRepoPath());
        }

        ProtoBufOption option = new ProtoBufOption();
        option.setCodecType(ProtoBuf.CodecType.FAST);
        byte[] epb = ProtoBuf.toByteArray(listMessage1s, option);
        System.out.println("+-fepb[" + epb.length + "]-------------------+");
        System.out.println(conver2HexStr(epb));
        System.out.println("+--------------------+");

        listMessage1s = ProtoBuf.toObject(epb, ArrayMessageNoAccess.class, option);
        list = (Proj[])fieldF.get(listMessage1s);
        assertEquals(list.length, pbOfs.getValueList().size());
        for (int i=0;i<list.length;i++) {
            OneMessageOuterClass.Proj pbOf = pbOfs.getValueList().get(i);
            Proj proj = list[i];
            assertEquals(pbOf.getId(), proj.getId());
            assertEquals(pbOf.getName(), proj.getName());
            assertEquals(pbOf.getRepoPath(), proj.getRepoPath());
        }
    }
}
