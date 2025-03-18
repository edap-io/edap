package io.edap.protobuf.test;

import io.edap.protobuf.*;
import io.edap.protobuf.model.ProtoBufOption;
import io.edap.protobuf.test.message.ext.MapAllTypeModel;
import io.edap.protobuf.test.message.ext.MapBoolKeyModel;
import io.edap.protobuf.test.message.v3.OneMap;
import io.edap.util.internal.GeneratorClassInfo;

import java.io.IOException;

import static io.edap.util.AsmUtil.saveJavaFile;

public class T {
    public static void main(String[] args) throws NoSuchFieldException, IOException {
        //ProtoBufEncoder<MapBoolKeyModel> encoder = ProtoBufCodecRegister.INSTANCE.getEncoder(MapBoolKeyModel.class);
        ProtoBufDecoder<MapBoolKeyModel> decoder = ProtoBufCodecRegister.INSTANCE.getDecoder(MapBoolKeyModel.class);


    }
}
