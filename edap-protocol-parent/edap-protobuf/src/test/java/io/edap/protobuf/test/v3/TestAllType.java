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

import com.google.protobuf.ByteString;
import io.edap.json.Eson;
import io.edap.json.JsonObject;
import io.edap.protobuf.CodecType;
import io.edap.protobuf.ProtoBuf;
import io.edap.protobuf.model.ProtoBufOption;
import io.edap.protobuf.test.message.v3.*;
import io.edap.util.ClazzUtil;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.Field;
import java.util.*;

import static io.edap.protobuf.test.TestUtil.conver2HexStr;
import static org.junit.jupiter.api.Assertions.*;


public class TestAllType {

    @ParameterizedTest
    @ValueSource(strings = {
            "{" +
                    "\"field1\":true," +
                    "\"field2\":\"abcdefwxyz\"," +
                    "\"field3\":31.415926," +
                    "\"field4\":\"WEB\"," +
                    "\"field5\":127," +
                    "\"field6\":5671506337319861521L," +
                    "\"field7\":3.1415," +
                    "\"field8\":128," +
                    "\"field9\":5671506337319861522L," +
                    "\"field10\":{\"edap\":{\"id\":1,\"name\":\"edap\",\"repoPath\":\"https://www.easyea.com/edap/edap.git\"}}," +
                    "\"field11\":{\"id\":2,\"name\":\"edao\",\"repoPath\":\"https://www.easyea.com/easyea/edao.git\"}," +
                    "\"field12\":129," +
                    "\"field13\":5671506337319861523L," +
                    "\"field14\":130," +
                    "\"field15\":5671506337319861524L," +
                    "\"field16\":\"abcdefgwxyz\"," +
                    "\"field17\":131," +
                    "\"field18\":5671506337319861525L" +
                    "}"
    })
    void testEncode(String v) {
        try {
            JsonObject jvalue = Eson.parseJsonObject(v);

            AllTypeOuterClass.AllType.Builder builder = AllTypeOuterClass.AllType.newBuilder();
            JsonObject jproj = (JsonObject) jvalue.get("field11");
            builder.setField1(jvalue.getBooleanValue("field1"));
            builder.setField2(ByteString.copyFromUtf8(jvalue.getString("field2")));
            builder.setField3(jvalue.getDoubleValue("field3"));
            builder.setField4(OneEnumOuterClass.Corpus.valueOf(jvalue.getString("field4")));
            builder.setField5(jvalue.getIntValue("field5"));
            builder.setField6(jvalue.getLongValue("field6"));
            builder.setField7(jvalue.getFloatValue("field7"));
            builder.setField8(jvalue.getIntValue("field8"));
            builder.setField9(jvalue.getLongValue("field9"));
            for (Map.Entry<String, Object> jv : jvalue.getJsonObject("field10").entrySet()) {
                JsonObject jp = (JsonObject) jv.getValue();
                OneMapOuterClass.Project.Builder pbuider = OneMapOuterClass.Project.newBuilder();
                pbuider.setId(jp.getLongValue("id"));
                pbuider.setName(jp.getString("name"));
                pbuider.setRepoPath(jp.getString("repoPath"));
                builder.putField10(jv.getKey(), pbuider.build());
            }
            OneMessageOuterClass.Proj.Builder projB = OneMessageOuterClass.Proj.newBuilder();

            projB.setId(jproj.getLongValue("id"));
            projB.setName(jproj.getString("name"));
            projB.setRepoPath(jproj.getString("repoPath"));
            builder.setField11(projB.build());
            builder.setField12(jvalue.getIntValue("field12"));
            builder.setField13(jvalue.getLongValue("field13"));
            builder.setField14(jvalue.getIntValue("field14"));
            builder.setField15(jvalue.getLongValue("field15"));
            builder.setField16(jvalue.getString("field16"));
            builder.setField17(jvalue.getIntValue("field17"));
            builder.setField18(jvalue.getLongValue("field18"));

            AllTypeOuterClass.AllType oi32 = builder.build();
            byte[] pb = oi32.toByteArray();


            System.out.println("+--------------------+");
            System.out.println(conver2HexStr(pb));
            Proj proj = new Proj();
            proj.setId(jproj.getLongValue("id"));
            proj.setName(jproj.getString("name"));
            proj.setRepoPath(jproj.getString("repoPath"));

            //System.out.println(JSONObject.toJSONString(proj, true));
            AllType allType = new AllType();
            allType.field1 = jvalue.getBooleanValue("field1");
            allType.field2 = jvalue.getString("field2").getBytes("utf-8");
            allType.field3 = jvalue.getDoubleValue("field3");
            allType.field4 = Corpus.valueOf(jvalue.getString("field4"));
            allType.field5 = jvalue.getIntValue("field5");
            allType.field6 = jvalue.getLongValue("field6");
            allType.field7 = jvalue.getFloatValue("field7");
            allType.field8 = jvalue.getIntValue("field8");
            allType.field8 = jvalue.getIntValue("field8");
            allType.field9 = jvalue.getLongValue("field9");
            Map<String, Project> projects = new HashMap<>();
            for (Map.Entry<String, Object> jv : jvalue.getJsonObject("field10").entrySet()) {
                JsonObject jp = (JsonObject) jv.getValue();
                Project project = new Project();
                project.setId(jp.getLongValue("id"));
                project.setName(jp.getString("name"));
                project.setRepoPath(jp.getString("repoPath"));
                projects.put(jv.getKey(), project);
            }
            allType.field10 = projects;
            allType.field11 = proj;
            allType.field12 = jvalue.getIntValue("field12");
            allType.field13 = jvalue.getLongValue("field13");
            allType.field14 = jvalue.getIntValue("field14");

            allType.field15 = jvalue.getLongValue("field15");
            allType.field16 = jvalue.getString("field16");
            allType.field17 = jvalue.getIntValue("field17");
            allType.field18 = jvalue.getLongValue("field18");

            byte[] epb = ProtoBuf.toByteArray(allType);
            System.out.println("+-[" + epb.length + "]-------------------+");
            System.out.println(conver2HexStr(epb));
            System.out.println("+--------------------+");
            assertArrayEquals(pb, epb);


            ProtoBufOption option = new ProtoBufOption();
            option.setCodecType(CodecType.FAST);
            epb = ProtoBuf.toByteArray(allType);
            System.out.println("+-[" + epb.length + "]-------------------+");
            System.out.println(conver2HexStr(epb));
            System.out.println("+--------------------+");

        } catch (Exception e) {
            fail(e);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{" +
                    "\"field1\":true," +
                    "\"field2\":\"abcdefwxyz\"," +
                    "\"field3\":31.415926," +
                    "\"field4\":\"WEB\"," +
                    "\"field5\":127," +
                    "\"field6\":5671506337319861521L," +
                    "\"field7\":3.1415," +
                    "\"field8\":128," +
                    "\"field9\":5671506337319861522L," +
                    "\"field10\":{\"edap\":{\"id\":1,\"name\":\"edap\",\"repoPath\":\"https://www.easyea.com/edap/edap.git\"}}," +
                    "\"field11\":{\"id\":2,\"name\":\"edao\",\"repoPath\":\"https://www.easyea.com/easyea/edao.git\"}," +
                    "\"field12\":129," +
                    "\"field13\":5671506337319861523L," +
                    "\"field14\":130," +
                    "\"field15\":5671506337319861524L," +
                    "\"field16\":\"abcdefgwxyz\"," +
                    "\"field17\":131," +
                    "\"field18\":5671506337319861525L" +
                    "}"
    })
    void testDecode(String v) {
        try {
            JsonObject jvalue = Eson.parseJsonObject(v);

            AllTypeOuterClass.AllType.Builder builder = AllTypeOuterClass.AllType.newBuilder();
            builder.setField1(jvalue.getBooleanValue("field1"));
            builder.setField2(ByteString.copyFromUtf8(jvalue.getString("field2")));
            builder.setField3(jvalue.getDoubleValue("field3"));
            builder.setField4(OneEnumOuterClass.Corpus.valueOf(jvalue.getString("field4")));
            builder.setField5(jvalue.getIntValue("field5"));
            builder.setField6(jvalue.getLongValue("field6"));
            builder.setField7(jvalue.getFloatValue("field7"));
            builder.setField8(jvalue.getIntValue("field8"));
            builder.setField9(jvalue.getLongValue("field9"));
            for (Map.Entry<String, Object> jv : jvalue.getJsonObject("field10").entrySet()) {
                JsonObject jp = (JsonObject) jv.getValue();
                OneMapOuterClass.Project.Builder pbuider = OneMapOuterClass.Project.newBuilder();
                pbuider.setId(jp.getLongValue("id"));
                pbuider.setName(jp.getString("name"));
                pbuider.setRepoPath(jp.getString("repoPath"));
                builder.putField10(jv.getKey(), pbuider.build());
            }
            OneMessageOuterClass.Proj.Builder projB = OneMessageOuterClass.Proj.newBuilder();
            JsonObject jproj = jvalue.getJsonObject("field11");
            projB.setId(jproj.getLongValue("id"));
            projB.setName(jproj.getString("name"));
            projB.setRepoPath(jproj.getString("repoPath"));
            builder.setField11(projB.build());
            builder.setField12(jvalue.getIntValue("field12"));
            builder.setField13(jvalue.getLongValue("field13"));
            builder.setField14(jvalue.getIntValue("field14"));
            builder.setField15(jvalue.getLongValue("field15"));
            builder.setField16(jvalue.getString("field16"));
            builder.setField17(jvalue.getIntValue("field17"));
            builder.setField18(jvalue.getLongValue("field18"));

            AllTypeOuterClass.AllType oi32 = builder.build();
            byte[] pb = oi32.toByteArray();

            System.out.println("+-epb[" + pb.length + "]-------------------+");
            System.out.println(conver2HexStr(pb));
            System.out.println("+--------------------+");
            AllTypeOuterClass.AllType pbOf = AllTypeOuterClass.AllType.parseFrom(pb);

            AllType allType = ProtoBuf.toObject(pb, AllType.class);
            equalsAllType(pbOf, allType);


            ProtoBufOption option = new ProtoBufOption();
            option.setCodecType(CodecType.FAST);
            byte[] epb = ProtoBuf.toByteArray(allType, option);
            System.out.println("+-epbf[" + epb.length + "]-------------------+");
            System.out.println(conver2HexStr(epb));
            System.out.println("+--------------------+");

            allType = ProtoBuf.toObject(epb, AllType.class, option);
            equalsAllType(pbOf, allType);
        } catch (Exception e) {
            fail(e);
        }
    }

    private void equalsAllType(AllTypeOuterClass.AllType pbOf, AllType allType) {
        assertEquals(pbOf.getField1(), allType.field1);
        assertArrayEquals(pbOf.getField2().toByteArray(), allType.field2);
        assertEquals(pbOf.getField3(), allType.field3);
        assertEquals(pbOf.getField4().name(), allType.field4.name());
        assertEquals(pbOf.getField5(), allType.field5);
        assertEquals(pbOf.getField6(), allType.field6);
        assertEquals(pbOf.getField7(), allType.field7);
        assertEquals(pbOf.getField8(), allType.field8);
        assertEquals(pbOf.getField9(), allType.field9);

        assertEquals(pbOf.getField10Map().size(), allType.field10.size());
        for (Map.Entry<String, OneMapOuterClass.Project> entry : pbOf.getField10Map().entrySet()) {
            Project project = allType.field10.get(entry.getKey());
            assertEquals(project.getId(), entry.getValue().getId());
            assertEquals(project.getName(), entry.getValue().getName());
            assertEquals(project.getRepoPath(), entry.getValue().getRepoPath());
        }
        OneMessageOuterClass.Proj pproj = pbOf.getField11();
        Proj proj = allType.field11;
        assertEquals(proj.getId(), pproj.getId());
        assertEquals(proj.getName(), pproj.getName());
        assertEquals(proj.getRepoPath(), pproj.getRepoPath());

        assertEquals(pbOf.getField12(), allType.field12);
        assertEquals(pbOf.getField13(), allType.field13);
        assertEquals(pbOf.getField14(), allType.field14);
        assertEquals(pbOf.getField15(), allType.field15);
        assertEquals(pbOf.getField16(), allType.field16);
        assertEquals(pbOf.getField17(), allType.field17);
        assertEquals(pbOf.getField18(), allType.field18);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{" +
                    "\"field1\":true," +
                    "\"field2\":\"abcdefwxyz\"," +
                    "\"field3\":31.415926," +
                    "\"field4\":\"WEB\"," +
                    "\"field5\":127," +
                    "\"field6\":5671506337319861521L," +
                    "\"field7\":3.1415," +
                    "\"field8\":128," +
                    "\"field9\":5671506337319861522L," +
                    "\"field10\":{\"edap\":{\"id\":1,\"name\":\"edap\",\"repoPath\":\"https://www.easyea.com/edap/edap.git\"}}," +
                    "\"field11\":{\"id\":2,\"name\":\"edao\",\"repoPath\":\"https://www.easyea.com/easyea/edao.git\"}," +
                    "\"field12\":129," +
                    "\"field13\":5671506337319861523L," +
                    "\"field14\":130," +
                    "\"field15\":5671506337319861524L," +
                    "\"field16\":\"abcdefgwxyz\"," +
                    "\"field17\":131," +
                    "\"field18\":5671506337319861525L" +
                    "}"
    })
    void testEncodeUnboxed(String v) {
        try {
            JsonObject jvalue = Eson.parseJsonObject(v);

            AllTypeOuterClass.AllType.Builder builder = AllTypeOuterClass.AllType.newBuilder();
            JsonObject jproj = jvalue.getJsonObject("field11");
            builder.setField1(jvalue.getBooleanValue("field1"));
            builder.setField2(ByteString.copyFromUtf8(jvalue.getString("field2")));
            builder.setField3(jvalue.getDoubleValue("field3"));
            builder.setField4(OneEnumOuterClass.Corpus.valueOf(jvalue.getString("field4")));
            builder.setField5(jvalue.getIntValue("field5"));
            builder.setField6(jvalue.getLongValue("field6"));
            builder.setField7(jvalue.getFloatValue("field7"));
            builder.setField8(jvalue.getIntValue("field8"));
            builder.setField9(jvalue.getLongValue("field9"));

            Set<String> keys = jvalue.getJsonObject("field10").keySet();
            List<String> mapKeys = new ArrayList<>();
            mapKeys.addAll(keys);
            Collections.sort(mapKeys);
            for (String key : mapKeys) {
                JsonObject jp = jvalue.getJsonObject("field10");
                OneMapOuterClass.Project.Builder pbuider = OneMapOuterClass.Project.newBuilder();
                pbuider.setId(jp.getLongValue("id"));
                pbuider.setName(jp.getString("name"));
                pbuider.setRepoPath(jp.getString("repoPath"));
                builder.putField10(key, pbuider.build());
            }
            OneMessageOuterClass.Proj.Builder projB = OneMessageOuterClass.Proj.newBuilder();

            projB.setId(jproj.getLongValue("id"));
            projB.setName(jproj.getString("name"));
            projB.setRepoPath(jproj.getString("repoPath"));

            builder.setField11(projB.build());
            builder.setField12(jvalue.getIntValue("field12"));
            builder.setField13(jvalue.getLongValue("field13"));
            builder.setField14(jvalue.getIntValue("field14"));
            builder.setField15(jvalue.getLongValue("field15"));
            builder.setField16(jvalue.getString("field16"));
            builder.setField17(jvalue.getIntValue("field17"));
            builder.setField18(jvalue.getLongValue("field18"));

            AllTypeOuterClass.AllType oi32 = builder.build();
            byte[] pb = oi32.toByteArray();


            System.out.println("+--------------------+");
            System.out.println(conver2HexStr(pb));
            Proj proj = new Proj();
            proj.setId(jproj.getLongValue("id"));
            proj.setName(jproj.getString("name"));
            proj.setRepoPath(jproj.getString("repoPath"));

            //System.out.println(JSONObject.toJSONString(proj, true));
            AllTypeUnboxed allType = new AllTypeUnboxed();
            allType.field1 = jvalue.getBooleanValue("field1");
            allType.field2 = jvalue.getString("field2").getBytes("utf-8");
            allType.field3 = jvalue.getDoubleValue("field3");
            allType.field4 = Corpus.valueOf(jvalue.getString("field4"));
            allType.field5 = jvalue.getIntValue("field5");
            allType.field6 = jvalue.getLongValue("field6");
            allType.field7 = jvalue.getFloatValue("field7");
            allType.field8 = jvalue.getIntValue("field8");
            allType.field9 = jvalue.getLongValue("field9");
            Map<String, Project> projects = new LinkedHashMap<>();
            for (String key : mapKeys) {
                JsonObject jp = jvalue.getJsonObject("field10");
                Project project = new Project();
                project.setId(jp.getLongValue("id"));
                project.setName(jp.getString("name"));
                project.setRepoPath(jp.getString("repoPath"));
                projects.put(key, project);
            }
            allType.field10 = projects;
            allType.field11 = proj;
            allType.field12 = jvalue.getIntValue("field12");
            allType.field13 = jvalue.getLongValue("field13");
            allType.field14 = jvalue.getIntValue("field14");

            allType.field15 = jvalue.getLongValue("field15");
            allType.field16 = jvalue.getString("field16");
            allType.field17 = jvalue.getIntValue("field17");
            allType.field18 = jvalue.getLongValue("field18");

            byte[] epb = ProtoBuf.toByteArray(allType);
            System.out.println("+-epb[" + epb.length + "]---------------------");
            System.out.println(conver2HexStr(epb));
            System.out.println("+--------------------+");
            assertArrayEquals(pb, epb);

            ProtoBufOption option = new ProtoBufOption();
            option.setCodecType(CodecType.FAST);
            epb = ProtoBuf.toByteArray(allType, option);
            System.out.println("+-epbf[" + epb.length + "]---------------------");
            System.out.println(conver2HexStr(epb));
            System.out.println("+--------------------+");

        } catch (Exception e) {
            fail(e);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{" +
                    "\"field1\":true," +
                    "\"field2\":\"abcdefwxyz\"," +
                    "\"field3\":31.415926," +
                    "\"field4\":\"WEB\"," +
                    "\"field5\":127," +
                    "\"field6\":5671506337319861521L," +
                    "\"field7\":3.1415," +
                    "\"field8\":128," +
                    "\"field9\":5671506337319861522L," +
                    "\"field10\":{\"edap\":{\"id\":1,\"name\":\"edap\",\"repoPath\":\"https://www.easyea.com/edap/edap.git\"}}," +
                    "\"field11\":{\"id\":2,\"name\":\"edao\",\"repoPath\":\"https://www.easyea.com/easyea/edao.git\"}," +
                    "\"field12\":129," +
                    "\"field13\":5671506337319861523L," +
                    "\"field14\":130," +
                    "\"field15\":5671506337319861524L," +
                    "\"field16\":\"abcdefgwxyz\"," +
                    "\"field17\":131," +
                    "\"field18\":5671506337319861525L" +
                    "}"
    })
    void testDecodeUnboxed(String v) {
        try {
            JsonObject jvalue = Eson.parseJsonObject(v);

            AllTypeOuterClass.AllType.Builder builder = AllTypeOuterClass.AllType.newBuilder();
            builder.setField1(jvalue.getBooleanValue("field1"));
            builder.setField2(ByteString.copyFromUtf8(jvalue.getString("field2")));
            builder.setField3(jvalue.getDoubleValue("field3"));
            builder.setField4(OneEnumOuterClass.Corpus.valueOf(jvalue.getString("field4")));
            builder.setField5(jvalue.getIntValue("field5"));
            builder.setField6(jvalue.getLongValue("field6"));
            builder.setField7(jvalue.getFloatValue("field7"));
            builder.setField8(jvalue.getIntValue("field8"));
            builder.setField9(jvalue.getLongValue("field9"));
            for (Map.Entry<String, Object> jv : jvalue.getJsonObject("field10").entrySet()) {
                JsonObject jp = (JsonObject) jv.getValue();
                OneMapOuterClass.Project.Builder pbuider = OneMapOuterClass.Project.newBuilder();
                pbuider.setId(jp.getLongValue("id"));
                pbuider.setName(jp.getString("name"));
                pbuider.setRepoPath(jp.getString("repoPath"));
                builder.putField10(jv.getKey(), pbuider.build());
            }
            OneMessageOuterClass.Proj.Builder projB = OneMessageOuterClass.Proj.newBuilder();
            JsonObject jproj = jvalue.getJsonObject("field11");
            projB.setId(jproj.getLongValue("id"));
            projB.setName(jproj.getString("name"));
            projB.setRepoPath(jproj.getString("repoPath"));
            builder.setField11(projB.build());
            builder.setField12(jvalue.getIntValue("field12"));
            builder.setField13(jvalue.getLongValue("field13"));
            builder.setField14(jvalue.getIntValue("field14"));
            builder.setField15(jvalue.getLongValue("field15"));
            builder.setField16(jvalue.getString("field16"));
            builder.setField17(jvalue.getIntValue("field17"));
            builder.setField18(jvalue.getLongValue("field18"));

            AllTypeOuterClass.AllType oi32 = builder.build();
            byte[] pb = oi32.toByteArray();


            AllTypeOuterClass.AllType pbOf = AllTypeOuterClass.AllType.parseFrom(pb);

            AllTypeUnboxed allType = ProtoBuf.toObject(pb, AllTypeUnboxed.class);
            equalsAllTypeUnboxed(pbOf, allType);


            ProtoBufOption option = new ProtoBufOption();
            option.setCodecType(CodecType.FAST);
            byte[] epb = ProtoBuf.toByteArray(allType, option);
            System.out.println("+-epbf[" + epb.length + "]-------------------+");
            System.out.println(conver2HexStr(epb));
            System.out.println("+--------------------+");

            allType = ProtoBuf.toObject(epb, AllTypeUnboxed.class, option);
            equalsAllTypeUnboxed(pbOf, allType);
        } catch (Exception e) {
            fail(e);
        }
    }

    private void equalsAllTypeUnboxed(AllTypeOuterClass.AllType pbOf, AllTypeUnboxed allType) {
        assertEquals(pbOf.getField1(), allType.field1);
        assertArrayEquals(pbOf.getField2().toByteArray(), allType.field2);
        assertEquals(pbOf.getField3(), allType.field3);
        assertEquals(pbOf.getField4().name(), allType.field4.name());
        assertEquals(pbOf.getField5(), allType.field5);
        assertEquals(pbOf.getField6(), allType.field6);
        assertEquals(pbOf.getField7(), allType.field7);
        assertEquals(pbOf.getField8(), allType.field8);
        assertEquals(pbOf.getField9(), allType.field9);

        assertEquals(pbOf.getField10Map().size(), allType.field10.size());
        for (Map.Entry<String, OneMapOuterClass.Project> entry : pbOf.getField10Map().entrySet()) {
            Project project = allType.field10.get(entry.getKey());
            assertEquals(project.getId(), entry.getValue().getId());
            assertEquals(project.getName(), entry.getValue().getName());
            assertEquals(project.getRepoPath(), entry.getValue().getRepoPath());
        }
        OneMessageOuterClass.Proj pproj = pbOf.getField11();
        Proj proj = allType.field11;
        assertEquals(proj.getId(), pproj.getId());
        assertEquals(proj.getName(), pproj.getName());
        assertEquals(proj.getRepoPath(), pproj.getRepoPath());

        assertEquals(pbOf.getField12(), allType.field12);
        assertEquals(pbOf.getField13(), allType.field13);
        assertEquals(pbOf.getField14(), allType.field14);
        assertEquals(pbOf.getField15(), allType.field15);
        assertEquals(pbOf.getField16(), allType.field16);
        assertEquals(pbOf.getField17(), allType.field17);
        assertEquals(pbOf.getField18(), allType.field18);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{" +
                    "\"field1\":true," +
                    "\"field2\":\"abcdefwxyz\"," +
                    "\"field3\":31.415926," +
                    "\"field4\":\"WEB\"," +
                    "\"field5\":127," +
                    "\"field6\":5671506337319861521L," +
                    "\"field7\":3.1415," +
                    "\"field8\":128," +
                    "\"field9\":5671506337319861522L," +
                    "\"field10\":{\"edap\":{\"id\":1,\"name\":\"edap\",\"repoPath\":\"https://www.easyea.com/edap/edap.git\"}}," +
                    "\"field11\":{\"id\":2,\"name\":\"edao\",\"repoPath\":\"https://www.easyea.com/easyea/edao.git\"}," +
                    "\"field12\":129," +
                    "\"field13\":5671506337319861523L," +
                    "\"field14\":130," +
                    "\"field15\":5671506337319861524L," +
                    "\"field16\":\"abcdefgwxyz\"," +
                    "\"field17\":131," +
                    "\"field18\":5671506337319861525L" +
                    "}"
    })
    void testEncodeNoAccess(String v) {
        try {
            JsonObject jvalue = Eson.parseJsonObject(v);

            AllTypeOuterClass.AllType.Builder builder = AllTypeOuterClass.AllType.newBuilder();
            JsonObject jproj = jvalue.getJsonObject("field11");
            builder.setField1(jvalue.getBooleanValue("field1"));
            builder.setField2(ByteString.copyFromUtf8(jvalue.getString("field2")));
            builder.setField3(jvalue.getDoubleValue("field3"));
            builder.setField4(OneEnumOuterClass.Corpus.valueOf(jvalue.getString("field4")));
            builder.setField5(jvalue.getIntValue("field5"));
            builder.setField6(jvalue.getLongValue("field6"));
            builder.setField7(jvalue.getFloatValue("field7"));
            builder.setField8(jvalue.getIntValue("field8"));
            builder.setField9(jvalue.getLongValue("field9"));
            for (Map.Entry<String, Object> jv : jvalue.getJsonObject("field10").entrySet()) {
                JsonObject jp = (JsonObject) jv.getValue();
                OneMapOuterClass.Project.Builder pbuider = OneMapOuterClass.Project.newBuilder();
                pbuider.setId(jp.getLongValue("id"));
                pbuider.setName(jp.getString("name"));
                pbuider.setRepoPath(jp.getString("repoPath"));
                builder.putField10(jv.getKey(), pbuider.build());
            }
            OneMessageOuterClass.Proj.Builder projB = OneMessageOuterClass.Proj.newBuilder();

            projB.setId(jproj.getLongValue("id"));
            projB.setName(jproj.getString("name"));
            projB.setRepoPath(jproj.getString("repoPath"));
            builder.setField11(projB.build());
            builder.setField12(jvalue.getIntValue("field12"));
            builder.setField13(jvalue.getLongValue("field13"));
            builder.setField14(jvalue.getIntValue("field14"));
            builder.setField15(jvalue.getLongValue("field15"));
            builder.setField16(jvalue.getString("field16"));
            builder.setField17(jvalue.getIntValue("field17"));
            builder.setField18(jvalue.getLongValue("field18"));

            AllTypeOuterClass.AllType oi32 = builder.build();
            byte[] pb = oi32.toByteArray();


            System.out.println("+--------------------+");
            System.out.println(conver2HexStr(pb));
            Proj proj = new Proj();
            proj.setId(jproj.getLongValue("id"));
            proj.setName(jproj.getString("name"));
            proj.setRepoPath(jproj.getString("repoPath"));

            Field field1F = ClazzUtil.getDeclaredField(AllTypeNoAccess.class, "field1");
            Field field2F = ClazzUtil.getDeclaredField(AllTypeNoAccess.class, "field2");
            Field field3F = ClazzUtil.getDeclaredField(AllTypeNoAccess.class, "field3");
            Field field4F = ClazzUtil.getDeclaredField(AllTypeNoAccess.class, "field4");
            Field field5F = ClazzUtil.getDeclaredField(AllTypeNoAccess.class, "field5");
            Field field6F = ClazzUtil.getDeclaredField(AllTypeNoAccess.class, "field6");
            Field field7F = ClazzUtil.getDeclaredField(AllTypeNoAccess.class, "field7");
            Field field8F = ClazzUtil.getDeclaredField(AllTypeNoAccess.class, "field8");
            Field field9F = ClazzUtil.getDeclaredField(AllTypeNoAccess.class, "field9");
            Field field10F = ClazzUtil.getDeclaredField(AllTypeNoAccess.class, "field10");
            Field field11F = ClazzUtil.getDeclaredField(AllTypeNoAccess.class, "field11");
            Field field12F = ClazzUtil.getDeclaredField(AllTypeNoAccess.class, "field12");
            Field field13F = ClazzUtil.getDeclaredField(AllTypeNoAccess.class, "field13");
            Field field14F = ClazzUtil.getDeclaredField(AllTypeNoAccess.class, "field14");
            Field field15F = ClazzUtil.getDeclaredField(AllTypeNoAccess.class, "field15");
            Field field16F = ClazzUtil.getDeclaredField(AllTypeNoAccess.class, "field16");
            Field field17F = ClazzUtil.getDeclaredField(AllTypeNoAccess.class, "field17");
            Field field18F = ClazzUtil.getDeclaredField(AllTypeNoAccess.class, "field18");
            field1F.setAccessible(true);
            field2F.setAccessible(true);
            field3F.setAccessible(true);
            field4F.setAccessible(true);
            field5F.setAccessible(true);
            field6F.setAccessible(true);
            field7F.setAccessible(true);
            field8F.setAccessible(true);
            field9F.setAccessible(true);
            field10F.setAccessible(true);
            field11F.setAccessible(true);
            field12F.setAccessible(true);
            field13F.setAccessible(true);
            field14F.setAccessible(true);
            field15F.setAccessible(true);
            field16F.setAccessible(true);
            field17F.setAccessible(true);
            field18F.setAccessible(true);

            //System.out.println(JSONObject.toJSONString(proj, true));
            AllTypeNoAccess allType = new AllTypeNoAccess();
            field1F.set(allType, jvalue.getBooleanValue("field1"));
            field2F.set(allType, jvalue.getString("field2").getBytes("utf-8"));
            field3F.set(allType, jvalue.getDoubleValue("field3"));
            field4F.set(allType, Corpus.valueOf(jvalue.getString("field4")));
            field5F.set(allType, jvalue.getIntValue("field5"));
            field6F.set(allType, jvalue.getLongValue("field6"));
            field7F.set(allType, jvalue.getFloatValue("field7"));
            field8F.set(allType, jvalue.getIntValue("field8"));
            field9F.set(allType, jvalue.getLongValue("field9"));
            Map<String, Project> projects = new HashMap<>();
            for (Map.Entry<String, Object> jv : jvalue.getJsonObject("field10").entrySet()) {
                JsonObject jp = (JsonObject) jv.getValue();
                Project project = new Project();
                project.setId(jp.getLongValue("id"));
                project.setName(jp.getString("name"));
                project.setRepoPath(jp.getString("repoPath"));
                projects.put(jv.getKey(), project);
            }
            field10F.set(allType, projects);
            field11F.set(allType, proj);
            field12F.set(allType, jvalue.getIntValue("field12"));
            field13F.set(allType, jvalue.getLongValue("field13"));
            field14F.set(allType, jvalue.getIntValue("field14"));

            field15F.set(allType, jvalue.getLongValue("field15"));
            field16F.set(allType, jvalue.getString("field16"));
            field17F.set(allType, jvalue.getIntValue("field17"));
            field18F.set(allType, jvalue.getLongValue("field18"));

            byte[] epb = ProtoBuf.toByteArray(allType);
            System.out.println("+-epb[" + epb.length + "]-------------------+");
            System.out.println(conver2HexStr(epb));
            System.out.println("+--------------------+");
            assertArrayEquals(pb, epb);


            epb = ProtoBuf.toByteArray(allType);
            System.out.println("+-epbf[" + epb.length + "]-------------------+");
            System.out.println(conver2HexStr(epb));
            System.out.println("+--------------------+");

        } catch (Exception e) {
            fail(e);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{" +
                    "\"field1\":true," +
                    "\"field2\":\"abcdefwxyz\"," +
                    "\"field3\":31.415926," +
                    "\"field4\":\"WEB\"," +
                    "\"field5\":127," +
                    "\"field6\":5671506337319861521L," +
                    "\"field7\":3.1415," +
                    "\"field8\":128," +
                    "\"field9\":5671506337319861522L," +
                    "\"field10\":{\"edap\":{\"id\":1,\"name\":\"edap\",\"repoPath\":\"https://www.easyea.com/edap/edap.git\"}}," +
                    "\"field11\":{\"id\":2,\"name\":\"edao\",\"repoPath\":\"https://www.easyea.com/easyea/edao.git\"}," +
                    "\"field12\":129," +
                    "\"field13\":5671506337319861523L," +
                    "\"field14\":130," +
                    "\"field15\":5671506337319861524L," +
                    "\"field16\":\"abcdefgwxyz\"," +
                    "\"field17\":131," +
                    "\"field18\":5671506337319861525L" +
                    "}"
    })
    void testEncodeUnboxedNoAccess(String v) {
        try {
            JsonObject jvalue = Eson.parseJsonObject(v);

            AllTypeOuterClass.AllType.Builder builder = AllTypeOuterClass.AllType.newBuilder();
            JsonObject jproj = jvalue.getJsonObject("field11");
            builder.setField1(jvalue.getBooleanValue("field1"));
            builder.setField2(ByteString.copyFromUtf8(jvalue.getString("field2")));
            builder.setField3(jvalue.getDoubleValue("field3"));
            builder.setField4(OneEnumOuterClass.Corpus.valueOf(jvalue.getString("field4")));
            builder.setField5(jvalue.getIntValue("field5"));
            builder.setField6(jvalue.getLongValue("field6"));
            builder.setField7(jvalue.getFloatValue("field7"));
            builder.setField8(jvalue.getIntValue("field8"));
            builder.setField9(jvalue.getLongValue("field9"));
            for (Map.Entry<String, Object> jv : jvalue.getJsonObject("field10").entrySet()) {
                JsonObject jp = (JsonObject) jv.getValue();
                OneMapOuterClass.Project.Builder pbuider = OneMapOuterClass.Project.newBuilder();
                pbuider.setId(jp.getLongValue("id"));
                pbuider.setName(jp.getString("name"));
                pbuider.setRepoPath(jp.getString("repoPath"));
                builder.putField10(jv.getKey(), pbuider.build());
            }
            OneMessageOuterClass.Proj.Builder projB = OneMessageOuterClass.Proj.newBuilder();

            projB.setId(jproj.getLongValue("id"));
            projB.setName(jproj.getString("name"));
            projB.setRepoPath(jproj.getString("repoPath"));
            builder.setField11(projB.build());
            builder.setField12(jvalue.getIntValue("field12"));
            builder.setField13(jvalue.getLongValue("field13"));
            builder.setField14(jvalue.getIntValue("field14"));
            builder.setField15(jvalue.getLongValue("field15"));
            builder.setField16(jvalue.getString("field16"));
            builder.setField17(jvalue.getIntValue("field17"));
            builder.setField18(jvalue.getLongValue("field18"));

            AllTypeOuterClass.AllType oi32 = builder.build();
            byte[] pb = oi32.toByteArray();


            System.out.println("+--------------------+");
            System.out.println(conver2HexStr(pb));
            Proj proj = new Proj();
            proj.setId(jproj.getLongValue("id"));
            proj.setName(jproj.getString("name"));
            proj.setRepoPath(jproj.getString("repoPath"));

            Field field1F = ClazzUtil.getDeclaredField(AllTypeUnboxedNoAccess.class, "field1");
            Field field2F = ClazzUtil.getDeclaredField(AllTypeUnboxedNoAccess.class, "field2");
            Field field3F = ClazzUtil.getDeclaredField(AllTypeUnboxedNoAccess.class, "field3");
            Field field4F = ClazzUtil.getDeclaredField(AllTypeUnboxedNoAccess.class, "field4");
            Field field5F = ClazzUtil.getDeclaredField(AllTypeUnboxedNoAccess.class, "field5");
            Field field6F = ClazzUtil.getDeclaredField(AllTypeUnboxedNoAccess.class, "field6");
            Field field7F = ClazzUtil.getDeclaredField(AllTypeUnboxedNoAccess.class, "field7");
            Field field8F = ClazzUtil.getDeclaredField(AllTypeUnboxedNoAccess.class, "field8");
            Field field9F = ClazzUtil.getDeclaredField(AllTypeUnboxedNoAccess.class, "field9");
            Field field10F = ClazzUtil.getDeclaredField(AllTypeUnboxedNoAccess.class, "field10");
            Field field11F = ClazzUtil.getDeclaredField(AllTypeUnboxedNoAccess.class, "field11");
            Field field12F = ClazzUtil.getDeclaredField(AllTypeUnboxedNoAccess.class, "field12");
            Field field13F = ClazzUtil.getDeclaredField(AllTypeUnboxedNoAccess.class, "field13");
            Field field14F = ClazzUtil.getDeclaredField(AllTypeUnboxedNoAccess.class, "field14");
            Field field15F = ClazzUtil.getDeclaredField(AllTypeUnboxedNoAccess.class, "field15");
            Field field16F = ClazzUtil.getDeclaredField(AllTypeUnboxedNoAccess.class, "field16");
            Field field17F = ClazzUtil.getDeclaredField(AllTypeUnboxedNoAccess.class, "field17");
            Field field18F = ClazzUtil.getDeclaredField(AllTypeUnboxedNoAccess.class, "field18");
            field1F.setAccessible(true);
            field2F.setAccessible(true);
            field3F.setAccessible(true);
            field4F.setAccessible(true);
            field5F.setAccessible(true);
            field6F.setAccessible(true);
            field7F.setAccessible(true);
            field8F.setAccessible(true);
            field9F.setAccessible(true);
            field10F.setAccessible(true);
            field11F.setAccessible(true);
            field12F.setAccessible(true);
            field13F.setAccessible(true);
            field14F.setAccessible(true);
            field15F.setAccessible(true);
            field16F.setAccessible(true);
            field17F.setAccessible(true);
            field18F.setAccessible(true);

            //System.out.println(JSONObject.toJSONString(proj, true));
            AllTypeUnboxedNoAccess allType = new AllTypeUnboxedNoAccess();
            field1F.set(allType, jvalue.getBooleanValue("field1"));
            field2F.set(allType, jvalue.getString("field2").getBytes("utf-8"));
            field3F.set(allType, jvalue.getDoubleValue("field3"));
            field4F.set(allType, Corpus.valueOf(jvalue.getString("field4")));
            field5F.set(allType, jvalue.getIntValue("field5"));
            field6F.set(allType, jvalue.getLongValue("field6"));
            field7F.set(allType, jvalue.getFloatValue("field7"));
            field8F.set(allType, jvalue.getIntValue("field8"));
            field9F.set(allType, jvalue.getLongValue("field9"));
            Map<String, Project> projects = new HashMap<>();
            for (Map.Entry<String, Object> jv : jvalue.getJsonObject("field10").entrySet()) {
                JsonObject jp = (JsonObject) jv.getValue();
                Project project = new Project();
                project.setId(jp.getLongValue("id"));
                project.setName(jp.getString("name"));
                project.setRepoPath(jp.getString("repoPath"));
                projects.put(jv.getKey(), project);
            }
            field10F.set(allType, projects);
            field11F.set(allType, proj);
            field12F.set(allType, jvalue.getIntValue("field12"));
            field13F.set(allType, jvalue.getLongValue("field13"));
            field14F.set(allType, jvalue.getIntValue("field14"));
            field15F.set(allType, jvalue.getLongValue("field15"));
            field16F.set(allType, jvalue.getString("field16"));
            field17F.set(allType, jvalue.getIntValue("field17"));
            field18F.set(allType, jvalue.getLongValue("field18"));

            byte[] epb = ProtoBuf.toByteArray(allType);
            System.out.println("+-epb[" + epb.length + "]-------------------+");
            System.out.println(conver2HexStr(epb));
            System.out.println("+--------------------+");
            assertArrayEquals(pb, epb);

            ProtoBufOption option = new ProtoBufOption();
            option.setCodecType(CodecType.FAST);
            epb = ProtoBuf.toByteArray(allType, option);
            System.out.println("+-epbf[" + epb.length + "]-------------------+");
            System.out.println(conver2HexStr(epb));
            System.out.println("+--------------------+");

        } catch (Exception e) {
            fail(e);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{" +
                    "\"field1\":true," +
                    "\"field2\":\"abcdefwxyz\"," +
                    "\"field3\":31.415926," +
                    "\"field4\":\"WEB\"," +
                    "\"field5\":127," +
                    "\"field6\":5671506337319861521L," +
                    "\"field7\":3.1415," +
                    "\"field8\":128," +
                    "\"field9\":5671506337319861522L," +
                    "\"field10\":{\"edap\":{\"id\":1,\"name\":\"edap\",\"repoPath\":\"https://www.easyea.com/edap/edap.git\"}}," +
                    "\"field11\":{\"id\":2,\"name\":\"edao\",\"repoPath\":\"https://www.easyea.com/easyea/edao.git\"}," +
                    "\"field12\":129," +
                    "\"field13\":5671506337319861523L," +
                    "\"field14\":130," +
                    "\"field15\":5671506337319861524L," +
                    "\"field16\":\"abcdefgwxyz\"," +
                    "\"field17\":131," +
                    "\"field18\":5671506337319861525L" +
                    "}"
    })
    void testDecodeNoAccess(String v) {
        try {
            JsonObject jvalue = Eson.parseJsonObject(v);

            AllTypeOuterClass.AllType.Builder builder = AllTypeOuterClass.AllType.newBuilder();
            builder.setField1(jvalue.getBooleanValue("field1"));
            builder.setField2(ByteString.copyFromUtf8(jvalue.getString("field2")));
            builder.setField3(jvalue.getDoubleValue("field3"));
            builder.setField4(OneEnumOuterClass.Corpus.valueOf(jvalue.getString("field4")));
            builder.setField5(jvalue.getIntValue("field5"));
            builder.setField6(jvalue.getLongValue("field6"));
            builder.setField7(jvalue.getFloatValue("field7"));
            builder.setField8(jvalue.getIntValue("field8"));
            builder.setField9(jvalue.getLongValue("field9"));
            for (Map.Entry<String, Object> jv : jvalue.getJsonObject("field10").entrySet()) {
                JsonObject jp = (JsonObject) jv.getValue();
                OneMapOuterClass.Project.Builder pbuider = OneMapOuterClass.Project.newBuilder();
                pbuider.setId(jp.getLongValue("id"));
                pbuider.setName(jp.getString("name"));
                pbuider.setRepoPath(jp.getString("repoPath"));
                builder.putField10(jv.getKey(), pbuider.build());
            }
            OneMessageOuterClass.Proj.Builder projB = OneMessageOuterClass.Proj.newBuilder();
            JsonObject jproj = jvalue.getJsonObject("field11");
            projB.setId(jproj.getLongValue("id"));
            projB.setName(jproj.getString("name"));
            projB.setRepoPath(jproj.getString("repoPath"));
            builder.setField11(projB.build());
            builder.setField12(jvalue.getIntValue("field12"));
            builder.setField13(jvalue.getLongValue("field13"));
            builder.setField14(jvalue.getIntValue("field14"));
            builder.setField15(jvalue.getLongValue("field15"));
            builder.setField16(jvalue.getString("field16"));
            builder.setField17(jvalue.getIntValue("field17"));
            builder.setField18(jvalue.getLongValue("field18"));

            AllTypeOuterClass.AllType oi32 = builder.build();
            byte[] pb = oi32.toByteArray();


            AllTypeOuterClass.AllType pbOf = AllTypeOuterClass.AllType.parseFrom(pb);

            AllTypeNoAccess allType = ProtoBuf.toObject(pb, AllTypeNoAccess.class);
            equalsNoAccess(pbOf, allType);


            ProtoBufOption option = new ProtoBufOption();
            option.setCodecType(CodecType.FAST);
            byte[] epb = ProtoBuf.toByteArray(allType, option);
            System.out.println("+-epbf[" + epb.length + "]-------------------+");
            System.out.println(conver2HexStr(epb));
            System.out.println("+--------------------+");

            allType = ProtoBuf.toObject(epb, AllTypeNoAccess.class, option);
            equalsNoAccess(pbOf, allType);
        } catch (Exception e) {
            fail(e);
        }
    }

    private void equalsNoAccess(AllTypeOuterClass.AllType pbOf, AllTypeNoAccess allType) throws NoSuchFieldException, IllegalAccessException {
        Field field1F = ClazzUtil.getDeclaredField(AllTypeNoAccess.class, "field1");
        Field field2F = ClazzUtil.getDeclaredField(AllTypeNoAccess.class, "field2");
        Field field3F = ClazzUtil.getDeclaredField(AllTypeNoAccess.class, "field3");
        Field field4F = ClazzUtil.getDeclaredField(AllTypeNoAccess.class, "field4");
        Field field5F = ClazzUtil.getDeclaredField(AllTypeNoAccess.class, "field5");
        Field field6F = ClazzUtil.getDeclaredField(AllTypeNoAccess.class, "field6");
        Field field7F = ClazzUtil.getDeclaredField(AllTypeNoAccess.class, "field7");
        Field field8F = ClazzUtil.getDeclaredField(AllTypeNoAccess.class, "field8");
        Field field9F = ClazzUtil.getDeclaredField(AllTypeNoAccess.class, "field9");
        Field field10F = ClazzUtil.getDeclaredField(AllTypeNoAccess.class, "field10");
        Field field11F = ClazzUtil.getDeclaredField(AllTypeNoAccess.class, "field11");
        Field field12F = ClazzUtil.getDeclaredField(AllTypeNoAccess.class, "field12");
        Field field13F = ClazzUtil.getDeclaredField(AllTypeNoAccess.class, "field13");
        Field field14F = ClazzUtil.getDeclaredField(AllTypeNoAccess.class, "field14");
        Field field15F = ClazzUtil.getDeclaredField(AllTypeNoAccess.class, "field15");
        Field field16F = ClazzUtil.getDeclaredField(AllTypeNoAccess.class, "field16");
        Field field17F = ClazzUtil.getDeclaredField(AllTypeNoAccess.class, "field17");
        Field field18F = ClazzUtil.getDeclaredField(AllTypeNoAccess.class, "field18");
        field1F.setAccessible(true);
        field2F.setAccessible(true);
        field3F.setAccessible(true);
        field4F.setAccessible(true);
        field5F.setAccessible(true);
        field6F.setAccessible(true);
        field7F.setAccessible(true);
        field8F.setAccessible(true);
        field9F.setAccessible(true);
        field10F.setAccessible(true);
        field11F.setAccessible(true);
        field12F.setAccessible(true);
        field13F.setAccessible(true);
        field14F.setAccessible(true);
        field15F.setAccessible(true);
        field16F.setAccessible(true);
        field17F.setAccessible(true);
        field18F.setAccessible(true);


        assertEquals(pbOf.getField1(), (Boolean) field1F.get(allType));
        assertArrayEquals(pbOf.getField2().toByteArray(), (byte[])field2F.get(allType));
        assertEquals(pbOf.getField3(), (Double)field3F.get(allType));
        assertEquals(pbOf.getField4().name(), ((Corpus)field4F.get(allType)).name());
        assertEquals(pbOf.getField5(), (Integer)field5F.get(allType));
        assertEquals(pbOf.getField6(), (Long)field6F.get(allType));
        assertEquals(pbOf.getField7(), (Float)field7F.get(allType));
        assertEquals(pbOf.getField8(), (Integer)field8F.get(allType));
        assertEquals(pbOf.getField9(), (Long)field9F.get(allType));

        Map<String, Project> f10 = (Map<String, Project>) field10F.get(allType);
        assertEquals(pbOf.getField10Map().size(), f10.size());
        for (Map.Entry<String, OneMapOuterClass.Project> entry : pbOf.getField10Map().entrySet()) {
            Project project = f10.get(entry.getKey());
            assertEquals(project.getId(), entry.getValue().getId());
            assertEquals(project.getName(), entry.getValue().getName());
            assertEquals(project.getRepoPath(), entry.getValue().getRepoPath());
        }
        OneMessageOuterClass.Proj pproj = pbOf.getField11();
        Proj proj = (Proj)field11F.get(allType);
        assertEquals(proj.getId(), pproj.getId());
        assertEquals(proj.getName(), pproj.getName());
        assertEquals(proj.getRepoPath(), pproj.getRepoPath());

        assertEquals(pbOf.getField12(), field12F.get(allType));
        assertEquals(pbOf.getField13(), field13F.get(allType));
        assertEquals(pbOf.getField14(), field14F.get(allType));
        assertEquals(pbOf.getField15(), field15F.get(allType));
        assertEquals(pbOf.getField16(), field16F.get(allType));
        assertEquals(pbOf.getField17(), field17F.get(allType));
        assertEquals(pbOf.getField18(), field18F.get(allType));
    }
}
