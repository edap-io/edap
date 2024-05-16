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
import io.edap.protobuf.ProtoFieldInfo;
import io.edap.protobuf.test.message.v3.*;
import io.edap.protobuf.util.ProtoUtil;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.lang.reflect.Field;
import java.lang.reflect.Type;
import java.util.*;

import static io.edap.protobuf.test.TestUtil.conver2HexStr;
import static java.lang.Double.doubleToLongBits;
import static java.lang.Float.floatToIntBits;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
                "\"field18\":5671506337319861525L," +
                "\"field19\":\"\"" +
                "}";
    }

    @Test
    public void testSkipBoolean() throws IOException, IllegalAccessException {
        AllType allType = buildAllType();
        byte[] epb = ProtoBuf.toByteArray(allType);
        System.out.println("+-epb[" + epb.length + "]---------------------");
        System.out.println(conver2HexStr(epb));
        System.out.println("+--------------------+");

        SkipBoolean skipBoolean = ProtoBuf.toObject(epb, SkipBoolean.class);
        assertTrue(isEquals(allType, skipBoolean));

        SkipByteArray skipByteArray = ProtoBuf.toObject(epb, SkipByteArray.class);
        assertTrue(isEquals(allType, skipByteArray));

        SkipDouble skipDouble = ProtoBuf.toObject(epb, SkipDouble.class);
        assertTrue(isEquals(allType, skipDouble));

        SkipEnum skipEnum = ProtoBuf.toObject(epb, SkipEnum.class);
        assertTrue(isEquals(allType, skipEnum));

        SkipFixed32 skipFixed32 = ProtoBuf.toObject(epb, SkipFixed32.class);
        assertTrue(isEquals(allType, skipFixed32));

        SkipSfixed64 skipFixed64 = ProtoBuf.toObject(epb, SkipSfixed64.class);
        assertTrue(isEquals(allType, skipFixed64));

        SkipFloat skipFloat = ProtoBuf.toObject(epb, SkipFloat.class);
        assertTrue(isEquals(allType, skipFloat));

        SkipInt32 skipInt32 = ProtoBuf.toObject(epb, SkipInt32.class);
        assertTrue(isEquals(allType, skipInt32));

        SkipInt64 skipInt64 = ProtoBuf.toObject(epb, SkipInt64.class);
        assertTrue(isEquals(allType, skipInt64));

        SkipMap skipMap = ProtoBuf.toObject(epb, SkipMap.class);
        assertTrue(isEquals(allType, skipMap));

        SkipMessage skipMessage = ProtoBuf.toObject(epb, SkipMessage.class);
        assertTrue(isEquals(allType, skipMessage));

        SkipSfixed32 skipSfixed32 = ProtoBuf.toObject(epb, SkipSfixed32.class);
        assertTrue(isEquals(allType, skipSfixed32));

        SkipSfixed64 skipSfixed64 = ProtoBuf.toObject(epb, SkipSfixed64.class);
        assertTrue(isEquals(allType, skipSfixed64));

        SkipSint32 skipSint32 = ProtoBuf.toObject(epb, SkipSint32.class);
        assertTrue(isEquals(allType, skipSint32));

        SkipSint64 skipSint64 = ProtoBuf.toObject(epb, SkipSint64.class);
        assertTrue(isEquals(allType, skipSint64));

        SkipString skipString = ProtoBuf.toObject(epb, SkipString.class);
        assertTrue(isEquals(allType, skipString));

        SkipUint32 skipUint32 = ProtoBuf.toObject(epb, SkipUint32.class);
        assertTrue(isEquals(allType, skipUint32));

        SkipUint64 skipUint64 = ProtoBuf.toObject(epb, SkipUint64.class);
        assertTrue(isEquals(allType, skipUint64));



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
        allType.field19 = jvalue.getString("field19");

        return allType;
    }

    private boolean isEquals(Object allType, Object skipObj) throws IOException, IllegalAccessException {
        List<ProtoFieldInfo> srdFields = ProtoUtil.getProtoFields(allType.getClass());
        List<ProtoFieldInfo> descFields = ProtoUtil.getProtoFields(skipObj.getClass());
        Map<String, Field> fields = new HashMap<>();
        for (ProtoFieldInfo sf : srdFields) {
            fields.put(sf.field.getName(), sf.field);
        }
        for (ProtoFieldInfo df : descFields) {
            Field descField = df.field;
            descField.setAccessible(true);
            Field srcField = fields.get(descField.getName());
            srcField.setAccessible(true);
            Type type = srcField.getGenericType();
            if (type instanceof Class) {
                switch (((Class)type).getName()) {
                    case "boolean":
                        if ((boolean)descField.get(skipObj) != (boolean)srcField.get(allType)) {
                            return false;
                        }
                        break;
                    case "java.lang.Boolean":
                        if (!isEquals((Boolean)descField.get(skipObj), (Boolean) srcField.get(allType))) {
                            return false;
                        }
                        break;
                    case "int":
                        if ((int)descField.get(skipObj) != (int)srcField.get(allType)) {
                            return false;
                        }
                        break;
                    case "java.lang.Integer":
                        if (!isEquals((Integer)descField.get(skipObj), (Integer) srcField.get(allType))) {
                            return false;
                        }
                        break;
                    case "long":
                        if ((long)descField.get(skipObj) != (long)srcField.get(allType)) {
                            return false;
                        }
                        break;
                    case "java.lang.Long":
                        if (!isEquals((Long)descField.get(skipObj), (Long) srcField.get(allType))) {
                            return false;
                        }
                        break;
                    case "float":
                        if (floatToIntBits((float)descField.get(skipObj)) != floatToIntBits((float)srcField.get(allType))) {
                            return false;
                        }
                        break;
                    case "java.lang.Float":
                        if (!isEquals((Float)descField.get(skipObj), (Float) srcField.get(allType))) {
                            return false;
                        }
                        break;
                    case "double":
                        if (doubleToLongBits((double)descField.get(skipObj)) != doubleToLongBits((double)srcField.get(allType))) {
                            return false;
                        }
                        break;
                    case "java.lang.Double":
                        if (!isEquals((Double)descField.get(skipObj), (Double) srcField.get(allType))) {
                            return false;
                        }
                        break;
                    case "short":
                        if (doubleToLongBits((short)descField.get(skipObj)) != doubleToLongBits((short)srcField.get(allType))) {
                            return false;
                        }
                        break;
                    case "java.lang.Short":
                        if (!isEquals((Short)descField.get(skipObj), (Short) srcField.get(allType))) {
                            return false;
                        }
                        break;
                    case "char":
                        if ((char)descField.get(skipObj) != (char)srcField.get(allType)) {
                            return false;
                        }
                        break;
                    case "java.lang.Character":
                        if (!isEquals((Character)descField.get(skipObj), (Character) srcField.get(allType))) {
                            return false;
                        }
                        break;
                    case "byte":
                        if ((byte)descField.get(skipObj) != (byte)srcField.get(allType)) {
                            return false;
                        }
                        break;
                    case "java.lang.Byte":
                        if (!isEquals((Byte)descField.get(skipObj), (Byte) srcField.get(allType))) {
                            return false;
                        }
                        break;
                    case "java.lang.String":
                        if (!isEquals((String)descField.get(skipObj), (String) srcField.get(allType))) {
                            return false;
                        }
                        break;
                    case "[B":
                        if (!isEquals((byte[])descField.get(skipObj), (byte[]) srcField.get(allType))) {
                            return false;
                        }
                        break;
                    default:
                        if (((Class)type).isEnum()) {
                            if (!descField.get(skipObj).getClass().getName().equals(srcField.get(allType).getClass().getName())) {
                                return false;
                            } else {
                                if (descField.get(skipObj) != srcField.get(allType)) {
                                    return false;
                                }
                            }
                        } else if (descField.get(skipObj) instanceof Map) {
                            Object srcMap = srcField.get(allType);
                            Object destMap = descField.get(skipObj);
                            if (!srcMap.getClass().getName().equals(destMap.getClass().getName())) {
                                return false;
                            } else {
                                Map src = (Map)srcMap;
                                Map dest = (Map)destMap;
                                if (!isEquals(src, dest)) {
                                    return false;
                                }
                            }
                        } else {
                            if (!isEquals(descField.get(skipObj), srcField.get(allType))) {
                                return false;
                            }
                        }
                }
            }
            System.out.println(descField.getName() + " skipObj's value=" + descField.get(skipObj) +
                    "    allType's value=" + srcField.get(allType));
        }
        return true;
    }

    private boolean isEquals(Map v1, Map v2) throws IOException, IllegalAccessException {
        if (v1.size() != v2.size()) {
            return false;
        }
        for (Object key : v1.keySet()) {
            if (!v1.containsKey(key)) {
                return false;
            }
            if (!isEquals(v1.get(key), v2.get(key))) {
                return false;
            }
        }
        return true;
    }

    private boolean isEquals(byte[] v1, byte[] v2) {
        if (v1 == null) {
            return v2 == null;
        }
        if (v1.length != v2.length) {
            return false;
        }
        for (int i=0;i<v1.length;i++) {
            if (v1[i] != v2[i]) {
                return false;
            }
        }
        return true;
    }

    private boolean isEquals(String v1, String v2) {
        if (v1 == null) {
            return v2 == null;
        }
        return v1.equals(v2);
    }

    private boolean isEquals(Byte v1, Byte v2) {
        if (v1 == null) {
            return v2 == null;
        }
        return v1.equals(v2);
    }

    private boolean isEquals(Character v1, Character v2) {
        if (v1 == null) {
            return v2 == null;
        }
        return v1.equals(v2);
    }

    private boolean isEquals(Short v1, Short v2) {
        if (v1 == null) {
            return v2 == null;
        }
        return v1.equals(v2);
    }

    private boolean isEquals(Double v1, Double v2) {
        if (v1 == null) {
            return v2 == null;
        }
        return v1.equals(v2);
    }

    private boolean isEquals(Float v1, Float v2) {
        if (v1 == null) {
            return v2 == null;
        }
        return v1.equals(v2);
    }

    private boolean isEquals(Long v1, Long v2) {
        if (v1 == null) {
            return v2 == null;
        }
        return v1.equals(v2);
    }

    private boolean isEquals(Integer v1, Integer v2) {
        if (v1 == null) {
            return v2 == null;
        }
        return v1.equals(v2);
    }

    private boolean isEquals(Boolean v1, Boolean v2) {
        if (v1 == null) {
            return v2 == null;
        }
        return v1.equals(v2);
    }
}
