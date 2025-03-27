package io.edap.protobuf.test;

import io.edap.protobuf.*;
import io.edap.protobuf.model.ProtoBufOption;
import io.edap.protobuf.test.message.ext.MapAllTypeModel;
import io.edap.protobuf.test.message.ext.MapBoolKeyModel;
import io.edap.protobuf.test.message.ext.MultiLangMap;
import io.edap.protobuf.test.message.v3.OneMap;
import io.edap.protobuf.test.message.v3.Project;
import io.edap.util.internal.GeneratorClassInfo;

import java.io.IOException;
import java.sql.Timestamp;
import java.util.*;

import static io.edap.util.AsmUtil.saveJavaFile;

public class T {
    public static void main(String[] args) throws NoSuchFieldException, IOException {

        ProtoBufEncoder<MultiLangMap> encoder = ProtoBufCodecRegister.INSTANCE.getEncoder(MultiLangMap.class);

        Map map = new HashMap();
        map.put("key1", 1);
        map.put("key2", new Date());
        map.put("key3", new Timestamp(new Date().getTime()));

        Object obj = map.values();
        System.out.println(obj);

        List<Object> list = new ArrayList<>();
        list.add(1);
        list.add(new Date());
        list.add(new Timestamp(new Date().getTime()));

        Object nobj = (Collection)list;

        System.out.println(obj);
    }
}
