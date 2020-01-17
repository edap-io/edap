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

import com.alibaba.fastjson.JSONObject;
import com.google.protobuf.ByteString;
import io.edap.protobuf.ProtoBuf;
import io.edap.protobuf.test.message.v3.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.HashMap;
import java.util.Map;

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
            JSONObject jvalue = JSONObject.parseObject(v);

            AllTypeOuterClass.AllType.Builder builder = AllTypeOuterClass.AllType.newBuilder();
            JSONObject jproj = jvalue.getJSONObject("field11");
            builder.setField1(jvalue.getBooleanValue("field1"));
            builder.setField2(ByteString.copyFromUtf8(jvalue.getString("field2")));
            builder.setField3(jvalue.getDoubleValue("field3"));
            builder.setField4(OneEnumOuterClass.Corpus.valueOf(jvalue.getString("field4")));
            builder.setField5(jvalue.getIntValue("field5"));
            builder.setField6(jvalue.getLongValue("field6"));
            builder.setField7(jvalue.getFloatValue("field7"));
            builder.setField8(jvalue.getIntValue("field8"));
            builder.setField9(jvalue.getLongValue("field9"));
            for (Map.Entry<String, Object> jv : jvalue.getJSONObject("field10").entrySet()) {
                JSONObject jp = (JSONObject) jv.getValue();
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
            for (Map.Entry<String, Object> jv : jvalue.getJSONObject("field10").entrySet()) {
                JSONObject jp = (JSONObject) jv.getValue();
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
            //System.out.println("----------------------");
            System.out.println(conver2HexStr(epb));
            System.out.println("+--------------------+");
            assertArrayEquals(pb, epb);
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
            JSONObject jvalue = JSONObject.parseObject(v);

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
            for (Map.Entry<String, Object> jv : jvalue.getJSONObject("field10").entrySet()) {
                JSONObject jp = (JSONObject) jv.getValue();
                OneMapOuterClass.Project.Builder pbuider = OneMapOuterClass.Project.newBuilder();
                pbuider.setId(jp.getLongValue("id"));
                pbuider.setName(jp.getString("name"));
                pbuider.setRepoPath(jp.getString("repoPath"));
                builder.putField10(jv.getKey(), pbuider.build());
            }
            OneMessageOuterClass.Proj.Builder projB = OneMessageOuterClass.Proj.newBuilder();
            JSONObject jproj = jvalue.getJSONObject("field11");
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
    void testEncodeUnboxed(String v) {
        try {
            JSONObject jvalue = JSONObject.parseObject(v);

            AllTypeOuterClass.AllType.Builder builder = AllTypeOuterClass.AllType.newBuilder();
            JSONObject jproj = jvalue.getJSONObject("field11");
            builder.setField1(jvalue.getBooleanValue("field1"));
            builder.setField2(ByteString.copyFromUtf8(jvalue.getString("field2")));
            builder.setField3(jvalue.getDoubleValue("field3"));
            builder.setField4(OneEnumOuterClass.Corpus.valueOf(jvalue.getString("field4")));
            builder.setField5(jvalue.getIntValue("field5"));
            builder.setField6(jvalue.getLongValue("field6"));
            builder.setField7(jvalue.getFloatValue("field7"));
            builder.setField8(jvalue.getIntValue("field8"));
            builder.setField9(jvalue.getLongValue("field9"));
            for (Map.Entry<String, Object> jv : jvalue.getJSONObject("field10").entrySet()) {
                JSONObject jp = (JSONObject) jv.getValue();
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
            AllTypeUnboxed allType = new AllTypeUnboxed();
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
            for (Map.Entry<String, Object> jv : jvalue.getJSONObject("field10").entrySet()) {
                JSONObject jp = (JSONObject) jv.getValue();
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
            //System.out.println("----------------------");
            System.out.println(conver2HexStr(epb));
            System.out.println("+--------------------+");
            assertArrayEquals(pb, epb);
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
            JSONObject jvalue = JSONObject.parseObject(v);

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
            for (Map.Entry<String, Object> jv : jvalue.getJSONObject("field10").entrySet()) {
                JSONObject jp = (JSONObject) jv.getValue();
                OneMapOuterClass.Project.Builder pbuider = OneMapOuterClass.Project.newBuilder();
                pbuider.setId(jp.getLongValue("id"));
                pbuider.setName(jp.getString("name"));
                pbuider.setRepoPath(jp.getString("repoPath"));
                builder.putField10(jv.getKey(), pbuider.build());
            }
            OneMessageOuterClass.Proj.Builder projB = OneMessageOuterClass.Proj.newBuilder();
            JSONObject jproj = jvalue.getJSONObject("field11");
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

            AllType allType = ProtoBuf.toObject(pb, AllType.class);


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
        } catch (Exception e) {
            fail(e);
        }
    }
}
