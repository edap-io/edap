package io.edap.protobuf.test;

import io.edap.protobuf.CodecType;
import io.edap.protobuf.MapEntryDecoderGenerator;
import io.edap.protobuf.MapEntryEncoderGenerator;
import io.edap.protobuf.model.ProtoBufOption;
import io.edap.protobuf.test.message.v3.OneMap;
import io.edap.util.internal.GeneratorClassInfo;

import java.io.IOException;

import static io.edap.util.AsmUtil.saveJavaFile;

public class T {
    public static void main(String[] args) throws NoSuchFieldException, IOException {
        MapEntryDecoderGenerator meg = new MapEntryDecoderGenerator(OneMap.class.getDeclaredField("value").getGenericType(), null);
        GeneratorClassInfo gci = meg.getClassInfo();
        byte[] bs = gci.clazzBytes;
        saveJavaFile("./" + gci.clazzName + ".class", bs);

        ProtoBufOption option = new ProtoBufOption();
        option.setCodecType(CodecType.FAST);
        meg = new MapEntryDecoderGenerator(OneMap.class.getDeclaredField("value").getGenericType(), option);
        gci = meg.getClassInfo();
        bs = gci.clazzBytes;
        saveJavaFile("./" + gci.clazzName + ".class", bs);
    }
}
