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
import java.util.Date;
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

    @ParameterizedTest
    @ValueSource(strings = {
            "",
            "a",
            "abcdefgh",
            "中文内容",
            "🐶头",
            "\u0080",
            "0x3C3F786D6C2076657273696F6E3D27312E302720656E636F64696E673D275554462D38273F3E203C646566696E6974696F6E7320786D6C6E733D22687474703A2F2F7777772E6F6D672E6F72672F737065632F42504D4E2F32303130303532342F4D4F44454C2220786D6C6E733A7873693D22687474703A2F2F7777772E77332E6F72672F323030312F584D4C536368656D612D696E7374616E63652220786D6C6E733A7873643D22687474703A2F2F7777772E77332E6F72672F323030312F584D4C536368656D612220786D6C6E733A61637469766974693D22687474703A2F2F61637469766974692E6F72672F62706D6E2220786D6C6E733A62706D6E64693D22687474703A2F2F7777772E6F6D672E6F72672F737065632F42504D4E2F32303130303532342F44492220786D6C6E733A6F6D6764633D22687474703A2F2F7777772E6F6D672E6F72672F737065632F44442F32303130303532342F44432220786D6C6E733A6F6D6764693D22687474703A2F2F7777772E6F6D672E6F72672F737065632F44442F32303130303532342F44492220747970654C616E67756167653D22687474703A2F2F7777772E77332E6F72672F323030312F584D4C536368656D61222065787072657373696F6E4C616E67756167653D22687474703A2F2F7777772E77332E6F72672F313939392F585061746822207461726765744E616D6573706163653D22687474703A2F2F61637469766974692E6F72672F74657374223E203C70726F636573732069643D2266726565666C6F77466F727761726422206E616D653D22E8BDACE58F91E887AAE794B1E6B581E7A88B2220697345786563757461626C653D2274727565222076657273696F6E49643D22322E30223E203C657874656E73696F6E456C656D656E74733E203C61637469766974693A657865637574696F6E4C697374656E6572206576656E743D2273746172742220636C6173733D22636F6D2E796F6E796F752E62706D2E6C697374656E65722E44656661756C745374617274457865637574696F6E4C697374656E6572222F3E203C61637469766974693A657865637574696F6E4C697374656E6572206576656E743D22656E642220636C6173733D22636F6D2E796F6E796F752E62706D2E6C697374656E65722E44656661756C74457865637574696F6E4C697374656E6572222F3E203C61637469766974693A657865637574696F6E4C697374656E6572206576656E743D22656E642220636C6173733D22636F6D2E796F6E796F752E62706D2E6D6573736167652E64656661756C74496D706C2E45736E4D65737361676553656E6441646170746572222F3E203C2F657874656E73696F6E456C656D656E74733E203C617070726F766553746172744576656E742069643D2273746172744576656E7433363839222061637469766974693A63616E436F6F7065726174696F6E3D2266616C7365222061637469766974693A73656E64546F5369676E6572494D3D2266616C7365222061637469766974693A73656E64546F416C6C55736572494D3D2266616C7365222061637469766974693A73656E64546F53746172746572494D3D2274727565222061637469766974693A63616E436F6D6D656E743D2274727565222061637469766974693A63616E466F72776172643D2274727565223E203C646F63756D656E746174696F6E3E6A756D70546F52656A65637441637469766974793B73656E64546F436F7079546F55736572733B7769746864726177416C6C3C2F646F63756D656E746174696F6E3E203C2F617070726F766553746172744576656E743E203C617070726F7665557365725461736B2069643D22617070726F7665557365725461736B3534393222206E616D653D22E5AEA1E689B9E4BBBBE58AA1222061637469766974693A77697468447261773D2266616C7365222061637469766974693A61737369676E41626C653D2266616C7365222061637469766974693A6164647369676E41626C653D2266616C7365222061637469766974693A72656A65637441626C653D2266616C7365222061637469766974693A63616E426552656A65637465643D2266616C7365222061637469766974693A64656C656761746541626C653D2274727565222061637469766974693A6164647369676E426568696E6441626C653D2266616C7365222061637469766974693A636F7079546F41626C653D2266616C7365222061637469766974693A72656A656374546F456E643D2266616C7365223E203C6D756C7469496E7374616E63654C6F6F7043686172616374657269737469637320697353657175656E7469616C3D2266616C7365222061637469766974693A636F6C6C656374696F6E3D22247B62706D4265616E2E67657455736572282671756F743B7B2770726F636573735061727469636970616E744974656D73273A5B7B2764657461696C73273A5B7B276964273A272461737369676E4C697374277D5D2C2764696664657074273A66616C73652C276C617374417070726F766553616D6564657074273A66616C73652C276C617374417070726F766553616D656F7267273A66616C73652C276C6173744170726F766553616D6544657074496E636C756465486967684C6576656C273A66616C73652C276C6173744170726F766553616D654F7267496E636C756465486967684C6576656C273A66616C73652C276D61726B65724F72674D6772496E636C75646548696768273A66616C73652C276E6F74496E636C7564655061727454696D65273A66616C73652C2773616D6564657074273A66616C73652C2773616D6564657074496E636C756465486967684C6576656C273A66616C73652C2773616D656F7267273A66616C73652C2773616D656F7267496E636C756465486967684C6576656C273A66616C73652C2774797065273A2741535349474E4C495354277D5D7D2671756F743B297D222061637469766974693A656C656D656E745661726961626C653D2261737369676E6565223E203C636F6D706C6574696F6E436F6E646974696F6E3E247B6E724F66436F6D706C65746564496E7374616E6365732F6E724F66496E7374616E6365733D3D317D3C2F636F6D706C6574696F6E436F6E646974696F6E3E203C2F6D756C7469496E7374616E63654C6F6F704368617261637465726973746963733E203C657874656E73696F6E456C656D656E74733E203C61637469766974693A657865637574696F6E4C697374656E6572206576656E743D22656E642220636C6173733D22636F6D2E796F6E796F752E62706D2E6C697374656E65722E4163746976697479456E64457865637574696F6E4C697374656E6572222F3E203C61637469766974693A657865637574696F6E4C697374656E6572206576656E743D2273746172742220636C6173733D22636F6D2E796F6E796F752E62706D2E6C697374656E65722E41637469766974795374617274457865637574696F6E4C697374656E6572222F3E203C61637469766974693A7461736B4C697374656E6572206576656E743D226372656174652220636C6173733D22636F6D2E796F6E796F752E62706D2E6D6573736167652E64656661756C74496D706C2E45736E4D65737361676553656E6441646170746572222F3E203C61637469766974693A7461736B4C697374656E6572206576656E743D22636F6D706C6574652220636C6173733D22636F6D2E796F6E796F752E62706D2E6D6573736167652E64656661756C74496D706C2E45736E4D65737361676553656E6441646170746572222F3E203C61637469766974693A7461736B4C697374656E6572206576656E743D226A756D702220636C6173733D22636F6D2E796F6E796F752E62706D2E6D6573736167652E64656661756C74496D706C2E45736E4D65737361676553656E6441646170746572222F3E203C61637469766974693A7461736B4C697374656E6572206576656E743D2277697468647261772220636C6173733D22636F6D2E796F6E796F752E62706D2E6D6573736167652E64656661756C74496D706C2E45736E4D65737361676553656E6441646170746572222F3E203C61637469766974693A7461736B4C697374656E6572206576656E743D226F757474696D652220636C6173733D22636F6D2E796F6E796F752E62706D2E6D6573736167652E64656661756C74496D706C2E45736E4D65737361676553656E6441646170746572222F3E203C61637469766974693A7461736B4C697374656E6572206576656E743D2264656C6574652220636C6173733D22636F6D2E796F6E796F752E62706D2E6D6573736167652E64656661756C74496D706C2E45736E4D65737361676553656E6441646170746572222F3E203C2F657874656E73696F6E456C656D656E74733E203C2F617070726F7665557365725461736B3E203C656E644576656E742069643D22656E644576656E7431343538222F3E203C73657175656E6365466C6F772069643D2253657175656E6365466C6F77313433362220736F757263655265663D2273746172744576656E743336383922207461726765745265663D22617070726F7665557365725461736B35343932222F3E203C73657175656E6365466C6F772069643D2253657175656E6365466C6F77363837312220736F757263655265663D22617070726F7665557365725461736B3534393222207461726765745265663D22656E644576656E7431343538222F3E203C2F70726F636573733E203C62706D6E64693A42504D4E4469616772616D2069643D2242504D4E4469616772616D5F70726F6365737339323931223E203C62706D6E64693A42504D4E506C616E652062706D6E456C656D656E743D2270726F6365737339323931222069643D2242504D4E506C616E655F70726F6365737339323931223E203C62706D6E64693A42504D4E53686170652062706D6E456C656D656E743D2273746172744576656E7433363839222069643D2242504D4E53686170655F73746172744576656E7433363839223E203C6F6D6764633A426F756E6473206865696768743D2232342E30222077696474683D2232342E302220783D2236302E302220793D2234322E30222F3E203C2F62706D6E64693A42504D4E53686170653E203C62706D6E64693A42504D4E53686170652062706D6E456C656D656E743D22617070726F7665557365725461736B35343932222069643D2242504D4E53686170655F617070726F7665557365725461736B35343932223E203C6F6D6764633A426F756E6473206865696768743D2236302E30222077696474683D223134342E302220783D223132302E302220793D2232342E30222F3E203C2F62706D6E64693A42504D4E53686170653E203C62706D6E64693A42504D4E53686170652062706D6E456C656D656E743D22656E644576656E7431343538222069643D2242504D4E53686170655F656E644576656E7431343538223E203C6F6D6764633A426F756E6473206865696768743D2232342E30222077696474683D2232342E302220783D223330302E302220793D2234322E30222F3E203C2F62706D6E64693A42504D4E53686170653E203C62706D6E64693A42504D4E456467652062706D6E456C656D656E743D2253657175656E6365466C6F7736383731222069643D2242504D4E456467655F53657175656E6365466C6F7736383731223E203C6F6D6764693A776179706F696E7420783D22312E302220793D22322E302220736567496E6465783D2230222F3E203C2F62706D6E64693A42504D4E456467653E203C62706D6E64693A42504D4E456467652062706D6E456C656D656E743D2253657175656E6365466C6F7731343336222069643D2242504D4E456467655F53657175656E6365466C6F7731343336223E203C6F6D6764693A776179706F696E7420783D22312E302220793D22322E302220736567496E6465783D2230222F3E203C2F62706D6E64693A42504D4E456467653E203C2F62706D6E64693A42504D4E506C616E653E203C2F62706D6E64693A42504D4E4469616772616D3E203C2F646566696E6974696F6E733E"
    })
    public void testSkipString(String str) {
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

    @Test
    public void testSkipObject() {
        String str = new Random().nextDouble() + "";

        ProtoBufOption option = new ProtoBufOption();
        option.setCodecType(ProtoBuf.CodecType.FAST);

        SkipSrc src;
        byte[] epb;

        src = new SkipSrc();
        src.field19 = str;
        src.setFieldObj(new Date());

        epb = ProtoBuf.toByteArray(src);
        System.out.println("+-epb[" + epb.length + "]-------------------+");
        System.out.println(conver2HexStr(epb));
        System.out.println("+--------------------+");

        SkipDest dest = ProtoBuf.toObject(epb, SkipDest.class);
        assertNotNull(dest);
        assertEquals(dest.field19, str);

        epb = ProtoBuf.toByteArray(src, option);
        System.out.println("+-epbf[" + epb.length + "]-------------------+");
        System.out.println(conver2HexStr(epb));
        System.out.println("+--------------------+");

        dest = ProtoBuf.toObject(epb, SkipDest.class, option);
        assertNotNull(dest);
        assertEquals(dest.field19, str);


        src = new SkipSrc();
        src.field19 = str;
        src.setFieldObj(null);

        epb = ProtoBuf.toByteArray(src);
        System.out.println("+-epb[" + epb.length + "]-------------------+");
        System.out.println(conver2HexStr(epb));
        System.out.println("+--------------------+");

        dest = ProtoBuf.toObject(epb, SkipDest.class);
        assertNotNull(dest);
        assertEquals(dest.field19, str);

        epb = ProtoBuf.toByteArray(src, option);
        System.out.println("+-epbf[" + epb.length + "]-------------------+");
        System.out.println(conver2HexStr(epb));
        System.out.println("+--------------------+");

        dest = ProtoBuf.toObject(epb, SkipDest.class, option);
        assertNotNull(dest);
        assertEquals(dest.field19, str);


        Proj proj = new Proj();
        proj.setId(9L);
        proj.setName("edap");
        proj.setRepoPath("https://www.easyea.com/edap/edap");
        src = new SkipSrc();
        src.field19 = str;
        src.setFieldObj(proj);

        epb = ProtoBuf.toByteArray(src);
        System.out.println("+-epb[" + epb.length + "]-------------------+");
        System.out.println(conver2HexStr(epb));
        System.out.println("+--------------------+");

        dest = ProtoBuf.toObject(epb, SkipDest.class);
        assertNotNull(dest);
        assertEquals(dest.field19, str);

        epb = ProtoBuf.toByteArray(src, option);
        System.out.println("+-epbf[" + epb.length + "]-------------------+");
        System.out.println(conver2HexStr(epb));
        System.out.println("+--------------------+");

        dest = ProtoBuf.toObject(epb, SkipDest.class, option);
        assertNotNull(dest);
        assertEquals(dest.field19, str);
    }

    @Test
    public void testSkipNestMessage() {
        String str = new Random().nextDouble() + "";

        Project project = new Project();
        project.setName("edap");
        project.setId(1L);
        project.setRepoPath("http://www.easyea.com/edap/edap.git");

        Random random = new Random();
        double d = random.nextDouble();
        float  f = random.nextFloat();
        int   ival = random.nextInt();
        int fixed32 = random.nextInt();
        long fixed64 = random.nextLong();
        int[] ints = new int[5];
        for (int i=0;i<5;i++) {
            ints[i] = random.nextInt();
        }
        SkipSrcInner srcInner = new SkipSrcInner();
        srcInner.setValDouble(d);
        srcInner.setValFixed32(fixed32);
        srcInner.setValFixed64(fixed64);
        srcInner.setValFloat(f);
        srcInner.setValInt(ival);
        srcInner.setValStr(str);
        srcInner.setProject(project);
        srcInner.setValObj(5);
        srcInner.setValIntArray(ints);

        SkipSrc src = new SkipSrc();
        src.setSkipInner(srcInner);
        src.field19 = str;

        byte[] epb = ProtoBuf.toByteArray(src);
        System.out.println("+-epb[" + epb.length + "]-------------------+");
        System.out.println(conver2HexStr(epb));
        System.out.println("+--------------------+");

        SkipDest dest = ProtoBuf.toObject(epb, SkipDest.class);
        assertNotNull(dest);
        assertEquals(dest.field19, str);

        ProtoBufOption option = new ProtoBufOption();
        option.setCodecType(ProtoBuf.CodecType.FAST);
        epb = ProtoBuf.toByteArray(src, option);
        System.out.println("+-epbf[" + epb.length + "]-------------------+");
        System.out.println(conver2HexStr(epb));
        System.out.println("+--------------------+");

        dest = ProtoBuf.toObject(epb, SkipDest.class, option);
        assertNotNull(dest);
        assertEquals(dest.field19, str);
    }
}
