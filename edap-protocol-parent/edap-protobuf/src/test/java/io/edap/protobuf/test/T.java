package io.edap.protobuf.test;

import io.edap.protobuf.MapEntryEncoderGenerator;
import io.edap.protobuf.test.message.v3.OneMap;
import io.edap.util.internal.GeneratorClassInfo;

import java.io.IOException;

import static io.edap.util.AsmUtil.saveJavaFile;

public class T {
    public static void main(String[] args) throws NoSuchFieldException, IOException {
        MapEntryEncoderGenerator meg = new MapEntryEncoderGenerator(OneMap.class.getDeclaredField("value").getGenericType());
        GeneratorClassInfo gci = meg.getClassInfo();
        byte[] bs = gci.clazzBytes;
        saveJavaFile("./" + gci.clazzName + ".class", bs);
    }
}
