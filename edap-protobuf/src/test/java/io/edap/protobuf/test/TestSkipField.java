package io.edap.protobuf.test;

import io.edap.json.Eson;
import io.edap.json.JsonObject;
import io.edap.protobuf.ProtoBuf;
import io.edap.protobuf.model.ProtoBufOption;
import io.edap.protobuf.test.message.v3.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.UnsupportedEncodingException;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;

import static io.edap.protobuf.test.TestUtil.conver2HexStr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

public class TestSkipField {

    @Test
    public void testSkipBoolean() {
        String str = new Random().nextDouble() + "";
        AllType allType = new AllType();
        allType.field1 = true;
        allType.field19 = str;

        byte[] epb = ProtoBuf.toByteArray(allType);
        System.out.println("+-pb[" + epb.length + "]-------------------+");
        System.out.println(conver2HexStr(epb));
        System.out.println("+--------------------+");

        SkipDest dest = ProtoBuf.toObject(epb, SkipDest.class);
        assertNotNull(dest);
        assertEquals(dest.field19, str);


        ProtoBufOption option = new ProtoBufOption();
        option.setCodecType(ProtoBuf.CodecType.FAST);
        epb = ProtoBuf.toByteArray(allType, option);
        System.out.println("+-pbf[" + epb.length + "]-------------------+");
        System.out.println(conver2HexStr(epb));
        System.out.println("+--------------------+");

        dest = ProtoBuf.toObject(epb, SkipDest.class, option);
        assertNotNull(dest);
        assertEquals(dest.field19, str);
    }

    @Test
    public void testSkipBytes() {
        String str = new Random().nextDouble() + "";
        AllType allType = new AllType();
        allType.field2 = (new Random().nextDouble()+"").getBytes();
        allType.field19 = str;

        byte[] epb = ProtoBuf.toByteArray(allType);
        System.out.println("+-pb[" + epb.length + "]-------------------+");
        System.out.println(conver2HexStr(epb));
        System.out.println("+--------------------+");

        SkipDest dest = ProtoBuf.toObject(epb, SkipDest.class);
        assertNotNull(dest);
        assertEquals(dest.field19, str);


        ProtoBufOption option = new ProtoBufOption();
        option.setCodecType(ProtoBuf.CodecType.FAST);
        epb = ProtoBuf.toByteArray(allType, option);
        System.out.println("+-pb[" + epb.length + "]-------------------+");
        System.out.println(conver2HexStr(epb));
        System.out.println("+--------------------+");

        dest = ProtoBuf.toObject(epb, SkipDest.class, option);
        assertNotNull(dest);
        assertEquals(dest.field19, str);
    }

    @Test
    public void testSkipDouble() {
        String str = new Random().nextDouble() + "";
        AllType allType = new AllType();
        allType.field3 = new Random().nextDouble();
        allType.field19 = str;

        byte[] epb = ProtoBuf.toByteArray(allType);
        System.out.println("+-pb[" + epb.length + "]-------------------+");
        System.out.println(conver2HexStr(epb));
        System.out.println("+--------------------+");

        SkipDest dest = ProtoBuf.toObject(epb, SkipDest.class);
        assertNotNull(dest);
        assertEquals(dest.field19, str);


        ProtoBufOption option = new ProtoBufOption();
        option.setCodecType(ProtoBuf.CodecType.FAST);
        epb = ProtoBuf.toByteArray(allType, option);
        System.out.println("+-pb[" + epb.length + "]-------------------+");
        System.out.println(conver2HexStr(epb));
        System.out.println("+--------------------+");

        dest = ProtoBuf.toObject(epb, SkipDest.class, option);
        assertNotNull(dest);
        assertEquals(dest.field19, str);
    }

    @Test
    public void testSkipEnum() {
        String str = new Random().nextDouble() + "";
        AllType allType = new AllType();
        allType.field4 = Corpus.IMAGES;
        allType.field19 = str;

        byte[] epb = ProtoBuf.toByteArray(allType);
        System.out.println("+-pb[" + epb.length + "]-------------------+");
        System.out.println(conver2HexStr(epb));
        System.out.println("+--------------------+");

        SkipDest dest = ProtoBuf.toObject(epb, SkipDest.class);
        assertNotNull(dest);
        assertEquals(dest.field19, str);


        ProtoBufOption option = new ProtoBufOption();
        option.setCodecType(ProtoBuf.CodecType.FAST);
        epb = ProtoBuf.toByteArray(allType, option);
        System.out.println("+-pb[" + epb.length + "]-------------------+");
        System.out.println(conver2HexStr(epb));
        System.out.println("+--------------------+");

        dest = ProtoBuf.toObject(epb, SkipDest.class, option);
        assertNotNull(dest);
        assertEquals(dest.field19, str);
    }

    @Test
    public void testSkipFixed32() {
        String str = new Random().nextDouble() + "";
        AllType allType = new AllType();
        allType.field5 = new Random().nextInt();
        allType.field19 = str;

        byte[] epb = ProtoBuf.toByteArray(allType);
        System.out.println("+-pb[" + epb.length + "]-------------------+");
        System.out.println(conver2HexStr(epb));
        System.out.println("+--------------------+");

        SkipDest dest = ProtoBuf.toObject(epb, SkipDest.class);
        assertNotNull(dest);
        assertEquals(dest.field19, str);


        ProtoBufOption option = new ProtoBufOption();
        option.setCodecType(ProtoBuf.CodecType.FAST);
        epb = ProtoBuf.toByteArray(allType, option);
        System.out.println("+-pb[" + epb.length + "]-------------------+");
        System.out.println(conver2HexStr(epb));
        System.out.println("+--------------------+");

        dest = ProtoBuf.toObject(epb, SkipDest.class, option);
        assertNotNull(dest);
        assertEquals(dest.field19, str);
    }

    @Test
    public void testSkipFixed64() {
        String str = new Random().nextDouble() + "";
        AllType allType = new AllType();
        allType.field6 = new Random().nextLong();
        allType.field19 = str;

        byte[] epb = ProtoBuf.toByteArray(allType);
        System.out.println("+-pb[" + epb.length + "]-------------------+");
        System.out.println(conver2HexStr(epb));
        System.out.println("+--------------------+");

        SkipDest dest = ProtoBuf.toObject(epb, SkipDest.class);
        assertNotNull(dest);
        assertEquals(dest.field19, str);


        ProtoBufOption option = new ProtoBufOption();
        option.setCodecType(ProtoBuf.CodecType.FAST);
        epb = ProtoBuf.toByteArray(allType, option);
        System.out.println("+-pb[" + epb.length + "]-------------------+");
        System.out.println(conver2HexStr(epb));
        System.out.println("+--------------------+");

        dest = ProtoBuf.toObject(epb, SkipDest.class, option);
        assertNotNull(dest);
        assertEquals(dest.field19, str);
    }

    @Test
    public void testSkipFloat() {
        String str = new Random().nextDouble() + "";
        AllType allType = new AllType();
        allType.field7 = new Random().nextFloat();
        allType.field19 = str;

        byte[] epb = ProtoBuf.toByteArray(allType);
        System.out.println("+-pb[" + epb.length + "]-------------------+");
        System.out.println(conver2HexStr(epb));
        System.out.println("+--------------------+");

        SkipDest dest = ProtoBuf.toObject(epb, SkipDest.class);
        assertNotNull(dest);
        assertEquals(dest.field19, str);


        ProtoBufOption option = new ProtoBufOption();
        option.setCodecType(ProtoBuf.CodecType.FAST);
        epb = ProtoBuf.toByteArray(allType, option);
        System.out.println("+-pb[" + epb.length + "]-------------------+");
        System.out.println(conver2HexStr(epb));
        System.out.println("+--------------------+");

        dest = ProtoBuf.toObject(epb, SkipDest.class, option);
        assertNotNull(dest);
        assertEquals(dest.field19, str);
    }

    @Test
    public void testSkipInt32() {
        String str = new Random().nextDouble() + "";
        AllType allType = new AllType();
        allType.field8 = new Random().nextInt();
        allType.field19 = str;

        byte[] epb = ProtoBuf.toByteArray(allType);
        System.out.println("+-pb[" + epb.length + "]-------------------+");
        System.out.println(conver2HexStr(epb));
        System.out.println("+--------------------+");

        SkipDest dest = ProtoBuf.toObject(epb, SkipDest.class);
        assertNotNull(dest);
        assertEquals(dest.field19, str);


        ProtoBufOption option = new ProtoBufOption();
        option.setCodecType(ProtoBuf.CodecType.FAST);
        epb = ProtoBuf.toByteArray(allType, option);
        System.out.println("+-pb[" + epb.length + "]-------------------+");
        System.out.println(conver2HexStr(epb));
        System.out.println("+--------------------+");

        dest = ProtoBuf.toObject(epb, SkipDest.class, option);
        assertNotNull(dest);
        assertEquals(dest.field19, str);
    }

    @Test
    public void testSkipInt64() {
        String str = new Random().nextDouble() + "";
        AllType allType = new AllType();
        allType.field9 = new Random().nextLong();
        allType.field19 = str;

        byte[] epb = ProtoBuf.toByteArray(allType);
        System.out.println("+-pb[" + epb.length + "]-------------------+");
        System.out.println(conver2HexStr(epb));
        System.out.println("+--------------------+");

        SkipDest dest = ProtoBuf.toObject(epb, SkipDest.class);
        assertNotNull(dest);
        assertEquals(dest.field19, str);


        ProtoBufOption option = new ProtoBufOption();
        option.setCodecType(ProtoBuf.CodecType.FAST);
        epb = ProtoBuf.toByteArray(allType, option);
        System.out.println("+-pb[" + epb.length + "]-------------------+");
        System.out.println(conver2HexStr(epb));
        System.out.println("+--------------------+");

        dest = ProtoBuf.toObject(epb, SkipDest.class, option);
        assertNotNull(dest);
        assertEquals(dest.field19, str);
    }

    @Test
    public void testSkipMap() {
        String str = new Random().nextDouble() + "";
        AllType allType = new AllType();
        Map<String, Project> map = new HashMap<>();
        Project edap = new Project();
        edap.setRepoPath("https://www.easyea.com/edap/edap.git");
        edap.setId(2L);
        edap.setName("edap");
        map.put("edap", edap);
        Project easyea = new Project();
        easyea.setRepoPath("https://www.easyea.com/easyea/easyea.git");
        easyea.setId(1L);
        easyea.setName("easyea");
        map.put("easyea", easyea);

        allType.field10 = map;
        allType.field19 = str;

        byte[] epb = ProtoBuf.toByteArray(allType);
        System.out.println("+-pb[" + epb.length + "]-------------------+");
        System.out.println(conver2HexStr(epb));
        System.out.println("+--------------------+");

        SkipDest dest = ProtoBuf.toObject(epb, SkipDest.class);
        assertNotNull(dest);
        assertEquals(dest.field19, str);


        ProtoBufOption option = new ProtoBufOption();
        option.setCodecType(ProtoBuf.CodecType.FAST);
        epb = ProtoBuf.toByteArray(allType, option);
        System.out.println("+-pb[" + epb.length + "]-------------------+");
        System.out.println(conver2HexStr(epb));
        System.out.println("+--------------------+");

        dest = ProtoBuf.toObject(epb, SkipDest.class, option);
        assertNotNull(dest);
        assertEquals(dest.field19, str);
    }

    @Test
    public void testSkipMessage() {
        String str = new Random().nextDouble() + "";
        AllType allType = new AllType();
        Proj edap = new Proj();
        edap.setRepoPath("https://www.easyea.com/edap/edap.git");
        edap.setId(2L);
        edap.setName("edap");

        allType.field11 = edap;
        allType.field19 = str;

        byte[] epb = ProtoBuf.toByteArray(allType);
        System.out.println("+-pb[" + epb.length + "]-------------------+");
        System.out.println(conver2HexStr(epb));
        System.out.println("+--------------------+");

        SkipDest dest = ProtoBuf.toObject(epb, SkipDest.class);
        assertNotNull(dest);
        assertEquals(dest.field19, str);


        ProtoBufOption option = new ProtoBufOption();
        option.setCodecType(ProtoBuf.CodecType.FAST);
        epb = ProtoBuf.toByteArray(allType, option);
        System.out.println("+-pb[" + epb.length + "]-------------------+");
        System.out.println(conver2HexStr(epb));
        System.out.println("+--------------------+");

        dest = ProtoBuf.toObject(epb, SkipDest.class, option);
        assertNotNull(dest);
        assertEquals(dest.field19, str);
    }

    @Test
    public void testSkipSfixed32() {
        String str = new Random().nextDouble() + "";
        AllType allType = new AllType();
        allType.field12 = new Random().nextInt();
        allType.field19 = str;

        byte[] epb = ProtoBuf.toByteArray(allType);
        System.out.println("+-pb[" + epb.length + "]-------------------+");
        System.out.println(conver2HexStr(epb));
        System.out.println("+--------------------+");

        SkipDest dest = ProtoBuf.toObject(epb, SkipDest.class);
        assertNotNull(dest);
        assertEquals(dest.field19, str);


        ProtoBufOption option = new ProtoBufOption();
        option.setCodecType(ProtoBuf.CodecType.FAST);
        epb = ProtoBuf.toByteArray(allType, option);
        System.out.println("+-pb[" + epb.length + "]-------------------+");
        System.out.println(conver2HexStr(epb));
        System.out.println("+--------------------+");

        dest = ProtoBuf.toObject(epb, SkipDest.class, option);
        assertNotNull(dest);
        assertEquals(dest.field19, str);
    }

    @Test
    public void testSkipSfixed64() {
        String str = new Random().nextDouble() + "";
        AllType allType = new AllType();
        allType.field13 = new Random().nextLong();
        allType.field19 = str;

        byte[] epb = ProtoBuf.toByteArray(allType);
        System.out.println("+-pb[" + epb.length + "]-------------------+");
        System.out.println(conver2HexStr(epb));
        System.out.println("+--------------------+");

        SkipDest dest = ProtoBuf.toObject(epb, SkipDest.class);
        assertNotNull(dest);
        assertEquals(dest.field19, str);


        ProtoBufOption option = new ProtoBufOption();
        option.setCodecType(ProtoBuf.CodecType.FAST);
        epb = ProtoBuf.toByteArray(allType, option);
        System.out.println("+-pb[" + epb.length + "]-------------------+");
        System.out.println(conver2HexStr(epb));
        System.out.println("+--------------------+");

        dest = ProtoBuf.toObject(epb, SkipDest.class, option);
        assertNotNull(dest);
        assertEquals(dest.field19, str);
    }

    @Test
    public void testSkipSint32() {
        String str = new Random().nextDouble() + "";
        AllType allType = new AllType();
        allType.field14 = new Random().nextInt();
        allType.field19 = str;

        byte[] epb = ProtoBuf.toByteArray(allType);
        System.out.println("+-pb[" + epb.length + "]-------------------+");
        System.out.println(conver2HexStr(epb));
        System.out.println("+--------------------+");

        SkipDest dest = ProtoBuf.toObject(epb, SkipDest.class);
        assertNotNull(dest);
        assertEquals(dest.field19, str);


        ProtoBufOption option = new ProtoBufOption();
        option.setCodecType(ProtoBuf.CodecType.FAST);
        epb = ProtoBuf.toByteArray(allType, option);
        System.out.println("+-pb[" + epb.length + "]-------------------+");
        System.out.println(conver2HexStr(epb));
        System.out.println("+--------------------+");

        dest = ProtoBuf.toObject(epb, SkipDest.class, option);
        assertNotNull(dest);
        assertEquals(dest.field19, str);
    }

    @Test
    public void testSkipSint64() {
        String str = new Random().nextDouble() + "";
        AllType allType = new AllType();
        allType.field15 = new Random().nextLong();
        allType.field19 = str;

        byte[] epb = ProtoBuf.toByteArray(allType);
        System.out.println("+-pb[" + epb.length + "]-------------------+");
        System.out.println(conver2HexStr(epb));
        System.out.println("+--------------------+");

        SkipDest dest = ProtoBuf.toObject(epb, SkipDest.class);
        assertNotNull(dest);
        assertEquals(dest.field19, str);


        ProtoBufOption option = new ProtoBufOption();
        option.setCodecType(ProtoBuf.CodecType.FAST);
        epb = ProtoBuf.toByteArray(allType, option);
        System.out.println("+-pb[" + epb.length + "]-------------------+");
        System.out.println(conver2HexStr(epb));
        System.out.println("+--------------------+");

        dest = ProtoBuf.toObject(epb, SkipDest.class, option);
        assertNotNull(dest);
        assertEquals(dest.field19, str);
    }

    @Test
    public void testSkipString() {
        String str = new Random().nextDouble() + "";
        AllType allType = new AllType();
        allType.field16 = new Random().nextLong() + "";
        allType.field19 = str;

        byte[] epb = ProtoBuf.toByteArray(allType);
        System.out.println("+-pb[" + epb.length + "]-------------------+");
        System.out.println(conver2HexStr(epb));
        System.out.println("+--------------------+");

        SkipDest dest = ProtoBuf.toObject(epb, SkipDest.class);
        assertNotNull(dest);
        assertEquals(dest.field19, str);


        ProtoBufOption option = new ProtoBufOption();
        option.setCodecType(ProtoBuf.CodecType.FAST);
        epb = ProtoBuf.toByteArray(allType, option);
        System.out.println("+-pb[" + epb.length + "]-------------------+");
        System.out.println(conver2HexStr(epb));
        System.out.println("+--------------------+");

        dest = ProtoBuf.toObject(epb, SkipDest.class, option);
        assertNotNull(dest);
        assertEquals(dest.field19, str);
    }

    @Test
    public void testSkipUint32() {
        String str = new Random().nextDouble() + "";
        AllType allType = new AllType();
        allType.field17 = new Random().nextInt();
        allType.field19 = str;

        byte[] epb = ProtoBuf.toByteArray(allType);
        System.out.println("+-pb[" + epb.length + "]-------------------+");
        System.out.println(conver2HexStr(epb));
        System.out.println("+--------------------+");

        SkipDest dest = ProtoBuf.toObject(epb, SkipDest.class);
        assertNotNull(dest);
        assertEquals(dest.field19, str);


        ProtoBufOption option = new ProtoBufOption();
        option.setCodecType(ProtoBuf.CodecType.FAST);
        epb = ProtoBuf.toByteArray(allType, option);
        System.out.println("+-pb[" + epb.length + "]-------------------+");
        System.out.println(conver2HexStr(epb));
        System.out.println("+--------------------+");

        dest = ProtoBuf.toObject(epb, SkipDest.class, option);
        assertNotNull(dest);
        assertEquals(dest.field19, str);

    }

    @Test
    public void testSkipUint64() {
        String str = new Random().nextDouble() + "";
        AllType allType = new AllType();
        allType.field18 = new Random().nextLong();
        allType.field19 = str;

        byte[] epb = ProtoBuf.toByteArray(allType);
        System.out.println("+-pb[" + epb.length + "]-------------------+");
        System.out.println(conver2HexStr(epb));
        System.out.println("+--------------------+");

        SkipDest dest = ProtoBuf.toObject(epb, SkipDest.class);
        assertNotNull(dest);
        assertEquals(dest.field19, str);


        ProtoBufOption option = new ProtoBufOption();
        option.setCodecType(ProtoBuf.CodecType.FAST);
        epb = ProtoBuf.toByteArray(allType, option);
        System.out.println("+-pb[" + epb.length + "]-------------------+");
        System.out.println(conver2HexStr(epb));
        System.out.println("+--------------------+");

        dest = ProtoBuf.toObject(epb, SkipDest.class, option);
        assertNotNull(dest);
        assertEquals(dest.field19, str);
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
    public void testAllType(String v) throws UnsupportedEncodingException {
        JsonObject jvalue = Eson.parseJsonObject(v);
        JsonObject jproj = (JsonObject) jvalue.get("field11");

        Proj proj = new Proj();
        proj.setId(jproj.getLongValue("id"));
        proj.setName(jproj.getString("name"));
        proj.setRepoPath(jproj.getString("repoPath"));

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

        String str = new Random().nextDouble() + "";
        allType.field19 = str;

        byte[] epb = ProtoBuf.toByteArray(allType);
        System.out.println("+-pb[" + epb.length + "]-------------------+");
        System.out.println(conver2HexStr(epb));
        System.out.println("+--------------------+");

        SkipDest dest = ProtoBuf.toObject(epb, SkipDest.class);
        assertNotNull(dest);
        assertEquals(dest.field19, str);


        ProtoBufOption option = new ProtoBufOption();
        option.setCodecType(ProtoBuf.CodecType.FAST);
        epb = ProtoBuf.toByteArray(allType, option);
        System.out.println("+-pb[" + epb.length + "]-------------------+");
        System.out.println(conver2HexStr(epb));
        System.out.println("+--------------------+");

        dest = ProtoBuf.toObject(epb, SkipDest.class, option);
        assertNotNull(dest);
        assertEquals(dest.field19, str);
    }
}
