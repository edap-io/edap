//
// Source code recreated from a .class file by IntelliJ IDEA
// (powered by FernFlower decompiler)
//

package pbd.io.edap.protobuf.test.message.ext;

import io.edap.protobuf.AbstractDecoder;
import io.edap.protobuf.MapEntryDecoder;
import io.edap.protobuf.ProtoBufCodecRegister;
import io.edap.protobuf.ProtoBufDecoder;
import io.edap.protobuf.ProtoBufReader;
import io.edap.protobuf.ProtoException;
import io.edap.protobuf.model.ProtoBufOption;
import io.edap.protobuf.test.message.ext.MapAllTypeModel;
import io.edap.protobuf.test.message.v3.Project;
import io.edap.util.AsmUtil;
import java.lang.reflect.Type;
import java.util.HashMap;

public class MapAllTypeModelDecoder extends AbstractDecoder implements ProtoBufDecoder<MapAllTypeModel> {
    private static ProtoBufOption PROTO_BUF_OPTION = new ProtoBufOption();
    private ProtoBufDecoder<Project> DecoderProject;
    private MapEntryDecoder<Byte, Project> mapEntryDecoder_f52fc934d6d4cab8e0a8f8702922a8c4;
    private MapEntryDecoder<Boolean, Project> mapEntryDecoder_196fc4f7ce50bb36e14feec4fa969615;
    private MapEntryDecoder<Character, Project> mapEntryDecoder_b1e161497feea69ded2681420b1b98c1;
    private MapEntryDecoder<Short, Project> mapEntryDecoder_a04df361498fbb39df800797e0cab67f;
    private MapEntryDecoder<Integer, Project> mapEntryDecoder_e423d656fd0a9d19987476e0c9015964;
    private MapEntryDecoder<Long, Project> mapEntryDecoder_3d84a81b47f1dfecec8142f89383ed7b;
    private MapEntryDecoder<Float, Project> mapEntryDecoder_0ef43131226a56e19b2fc2baea2b7e28;
    private MapEntryDecoder<Double, Project> mapEntryDecoder_423ad1431aee23399cd109148d501963;
    private MapEntryDecoder<String, Byte> mapEntryDecoder_408b7cf3aee2698cead1fd27f1d17659;
    private MapEntryDecoder<String, Boolean> mapEntryDecoder_5e6d01e0670a3a6eb345bd195fa722ab;
    private MapEntryDecoder<String, Character> mapEntryDecoder_a642cd1a6ec19c46e1ad02a7f8d48740;
    private MapEntryDecoder<String, Short> mapEntryDecoder_c1e5dc614674b460f027956d94549ced;
    private MapEntryDecoder<String, Integer> mapEntryDecoder_2ebdb0e4d1df3aff10093c4fb5957185;
    private MapEntryDecoder<String, Long> mapEntryDecoder_fcce79d0da4994a673ca3184da3dbc17;
    private MapEntryDecoder<String, Float> mapEntryDecoder_11d48685ac3a46ac02768696c79510de;
    private MapEntryDecoder<String, Double> mapEntryDecoder_04fe1607f52f587ff619ec6a1ba8ece1;

    public MapAllTypeModelDecoder() {
        this.DecoderProject = ProtoBufCodecRegister.INSTANCE.getDecoder(Project.class, PROTO_BUF_OPTION);
        Type var1 = AsmUtil.getFieldType(MapAllTypeModel.class, "byteKey");
        this.mapEntryDecoder_f52fc934d6d4cab8e0a8f8702922a8c4 = ProtoBufCodecRegister.INSTANCE.getMapEntryDecoder(var1, (Class)null, PROTO_BUF_OPTION);
        var1 = AsmUtil.getFieldType(MapAllTypeModel.class, "boolKey");
        this.mapEntryDecoder_196fc4f7ce50bb36e14feec4fa969615 = ProtoBufCodecRegister.INSTANCE.getMapEntryDecoder(var1, (Class)null, PROTO_BUF_OPTION);
        var1 = AsmUtil.getFieldType(MapAllTypeModel.class, "charKey");
        this.mapEntryDecoder_b1e161497feea69ded2681420b1b98c1 = ProtoBufCodecRegister.INSTANCE.getMapEntryDecoder(var1, (Class)null, PROTO_BUF_OPTION);
        var1 = AsmUtil.getFieldType(MapAllTypeModel.class, "shortKey");
        this.mapEntryDecoder_a04df361498fbb39df800797e0cab67f = ProtoBufCodecRegister.INSTANCE.getMapEntryDecoder(var1, (Class)null, PROTO_BUF_OPTION);
        var1 = AsmUtil.getFieldType(MapAllTypeModel.class, "intKey");
        this.mapEntryDecoder_e423d656fd0a9d19987476e0c9015964 = ProtoBufCodecRegister.INSTANCE.getMapEntryDecoder(var1, (Class)null, PROTO_BUF_OPTION);
        var1 = AsmUtil.getFieldType(MapAllTypeModel.class, "longKey");
        this.mapEntryDecoder_3d84a81b47f1dfecec8142f89383ed7b = ProtoBufCodecRegister.INSTANCE.getMapEntryDecoder(var1, (Class)null, PROTO_BUF_OPTION);
        var1 = AsmUtil.getFieldType(MapAllTypeModel.class, "floatKey");
        this.mapEntryDecoder_0ef43131226a56e19b2fc2baea2b7e28 = ProtoBufCodecRegister.INSTANCE.getMapEntryDecoder(var1, (Class)null, PROTO_BUF_OPTION);
        var1 = AsmUtil.getFieldType(MapAllTypeModel.class, "doubleKey");
        this.mapEntryDecoder_423ad1431aee23399cd109148d501963 = ProtoBufCodecRegister.INSTANCE.getMapEntryDecoder(var1, (Class)null, PROTO_BUF_OPTION);
        var1 = AsmUtil.getFieldType(MapAllTypeModel.class, "byteVal");
        this.mapEntryDecoder_408b7cf3aee2698cead1fd27f1d17659 = ProtoBufCodecRegister.INSTANCE.getMapEntryDecoder(var1, (Class)null, PROTO_BUF_OPTION);
        var1 = AsmUtil.getFieldType(MapAllTypeModel.class, "boolVal");
        this.mapEntryDecoder_5e6d01e0670a3a6eb345bd195fa722ab = ProtoBufCodecRegister.INSTANCE.getMapEntryDecoder(var1, (Class)null, PROTO_BUF_OPTION);
        var1 = AsmUtil.getFieldType(MapAllTypeModel.class, "charVal");
        this.mapEntryDecoder_a642cd1a6ec19c46e1ad02a7f8d48740 = ProtoBufCodecRegister.INSTANCE.getMapEntryDecoder(var1, (Class)null, PROTO_BUF_OPTION);
        var1 = AsmUtil.getFieldType(MapAllTypeModel.class, "shortVal");
        this.mapEntryDecoder_c1e5dc614674b460f027956d94549ced = ProtoBufCodecRegister.INSTANCE.getMapEntryDecoder(var1, (Class)null, PROTO_BUF_OPTION);
        var1 = AsmUtil.getFieldType(MapAllTypeModel.class, "intVal");
        this.mapEntryDecoder_2ebdb0e4d1df3aff10093c4fb5957185 = ProtoBufCodecRegister.INSTANCE.getMapEntryDecoder(var1, (Class)null, PROTO_BUF_OPTION);
        var1 = AsmUtil.getFieldType(MapAllTypeModel.class, "longVal");
        this.mapEntryDecoder_fcce79d0da4994a673ca3184da3dbc17 = ProtoBufCodecRegister.INSTANCE.getMapEntryDecoder(var1, (Class)null, PROTO_BUF_OPTION);
        var1 = AsmUtil.getFieldType(MapAllTypeModel.class, "floatVal");
        this.mapEntryDecoder_11d48685ac3a46ac02768696c79510de = ProtoBufCodecRegister.INSTANCE.getMapEntryDecoder(var1, (Class)null, PROTO_BUF_OPTION);
        var1 = AsmUtil.getFieldType(MapAllTypeModel.class, "doubleVal");
        this.mapEntryDecoder_04fe1607f52f587ff619ec6a1ba8ece1 = ProtoBufCodecRegister.INSTANCE.getMapEntryDecoder(var1, (Class)null, PROTO_BUF_OPTION);
    }

    private MapAllTypeModel doDecode(ProtoBufReader var1, int var2) throws ProtoException {
        MapAllTypeModel var3 = new MapAllTypeModel();
        HashMap var4 = new HashMap();
        HashMap var5 = new HashMap();
        HashMap var6 = new HashMap();
        HashMap var7 = new HashMap();
        HashMap var8 = new HashMap();
        HashMap var9 = new HashMap();
        HashMap var10 = new HashMap();
        HashMap var11 = new HashMap();
        HashMap var12 = new HashMap();
        HashMap var13 = new HashMap();
        HashMap var14 = new HashMap();
        HashMap var15 = new HashMap();
        HashMap var16 = new HashMap();
        HashMap var17 = new HashMap();
        HashMap var18 = new HashMap();
        HashMap var19 = new HashMap();
        boolean var20 = false;

        while(!var20) {
            int var21 = var1.readTag();
            switch (var21) {
                case 0:
                    var20 = true;
                    break;
                case 8:
                    var3.setPk(var1.readInt64());
                    break;
                case 18:
                    this.mapEntryDecoder_f52fc934d6d4cab8e0a8f8702922a8c4.decode(var1, var4);
                    break;
                case 26:
                    this.mapEntryDecoder_196fc4f7ce50bb36e14feec4fa969615.decode(var1, var5);
                    break;
                case 34:
                    this.mapEntryDecoder_b1e161497feea69ded2681420b1b98c1.decode(var1, var6);
                    break;
                case 42:
                    this.mapEntryDecoder_a04df361498fbb39df800797e0cab67f.decode(var1, var7);
                    break;
                case 50:
                    this.mapEntryDecoder_e423d656fd0a9d19987476e0c9015964.decode(var1, var8);
                    break;
                case 58:
                    this.mapEntryDecoder_3d84a81b47f1dfecec8142f89383ed7b.decode(var1, var9);
                    break;
                case 66:
                    this.mapEntryDecoder_0ef43131226a56e19b2fc2baea2b7e28.decode(var1, var10);
                    break;
                case 74:
                    this.mapEntryDecoder_423ad1431aee23399cd109148d501963.decode(var1, var11);
                    break;
                case 82:
                    this.mapEntryDecoder_408b7cf3aee2698cead1fd27f1d17659.decode(var1, var12);
                    break;
                case 90:
                    this.mapEntryDecoder_5e6d01e0670a3a6eb345bd195fa722ab.decode(var1, var13);
                    break;
                case 98:
                    this.mapEntryDecoder_a642cd1a6ec19c46e1ad02a7f8d48740.decode(var1, var14);
                    break;
                case 106:
                    this.mapEntryDecoder_c1e5dc614674b460f027956d94549ced.decode(var1, var15);
                    break;
                case 114:
                    this.mapEntryDecoder_2ebdb0e4d1df3aff10093c4fb5957185.decode(var1, var16);
                    break;
                case 122:
                    this.mapEntryDecoder_fcce79d0da4994a673ca3184da3dbc17.decode(var1, var17);
                    break;
                case 130:
                    this.mapEntryDecoder_11d48685ac3a46ac02768696c79510de.decode(var1, var18);
                    break;
                case 138:
                    this.mapEntryDecoder_04fe1607f52f587ff619ec6a1ba8ece1.decode(var1, var19);
                    break;
                default:
                    var1.skipField(var21, var21);
            }
        }

        var3.setByteKey(var4);
        var3.setBoolKey(var5);
        var3.setCharKey(var6);
        var3.setShortKey(var7);
        var3.setIntKey(var8);
        var3.setLongKey(var9);
        var3.setFloatKey(var10);
        var3.setDoubleKey(var11);
        var3.setByteVal(var12);
        var3.setBoolVal(var13);
        var3.setCharVal(var14);
        var3.setShortVal(var15);
        var3.setIntVal(var16);
        var3.setLongVal(var17);
        var3.setFloatVal(var18);
        var3.setDoubleVal(var19);
        return var3;
    }

    public MapAllTypeModel decode(ProtoBufReader var1) throws ProtoException {
        return this.doDecode(var1, 0);
    }
}
