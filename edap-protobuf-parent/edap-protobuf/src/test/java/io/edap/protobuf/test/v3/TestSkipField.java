/*
 * Copyright 2024 The edap Project
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

import io.edap.json.Eson;
import io.edap.json.JsonObject;
import io.edap.protobuf.ProtoBuf;
import io.edap.protobuf.test.message.v3.*;
import org.junit.jupiter.api.Test;

import java.io.UnsupportedEncodingException;
import java.util.*;

import static io.edap.protobuf.test.TestUtil.conver2HexStr;

public class TestSkipField {


    private static final String json;

    static {
        json = "{" +
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
                "}";
    }

    @Test
    public void testSkipBoolean() throws UnsupportedEncodingException {
        byte[] epb = ProtoBuf.toByteArray(buildAllType());
        System.out.println("+-epb[" + epb.length + "]---------------------");
        System.out.println(conver2HexStr(epb));
        System.out.println("+--------------------+");

        SkipBoolean skipBoolean = ProtoBuf.toObject(epb, SkipBoolean.class);
        System.out.println(skipBoolean);
    }

    private AllType buildAllType() throws UnsupportedEncodingException {
        JsonObject jvalue = Eson.parseJsonObject(json);
        JsonObject jproj = jvalue.getJsonObject("field11");
        Set<String> keys = jvalue.getJsonObject("field10").keySet();
        List<String> mapKeys = new ArrayList<>();
        mapKeys.addAll(keys);

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

        return allType;
    }
}
