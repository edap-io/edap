package io.edap.protobuf.test;

import io.edap.protobuf.*;
import io.edap.protobuf.test.message.ext.MultiLangMap;
import io.edap.protobuf.test.message.v3.OneSet;

import java.io.IOException;
import java.sql.Timestamp;
import java.util.*;

public class T {
    public static void main(String[] args) throws NoSuchFieldException, IOException, EncodeException {

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

        Random random = new Random();
        OneSet oneSet = new OneSet();
        Set<Long> set = new HashSet<>();
        set.add(random.nextLong());
        set.add(random.nextLong());
        set.add(random.nextLong());
        set.add(random.nextLong());
        set.add(random.nextLong());
        oneSet.setValues(set);

        ProtoBufEncoder<OneSet> oneSetEncoder = ProtoBufCodecRegister.INSTANCE.getEncoder(OneSet.class);

        ProtoBuf.ser(oneSet);

    }
}
