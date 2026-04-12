package io.edap.protobuf.test.ext;

import io.edap.protobuf.*;
import io.edap.protobuf.test.message.ext.MapAllTypeModel;
import io.edap.protobuf.test.message.ext.MapBoolKeyModel;
import io.edap.protobuf.test.message.ext.MapBoolValModel;
import io.edap.protobuf.test.message.ext.MapListModel;
import io.edap.protobuf.test.message.v3.Project;
import org.junit.jupiter.api.Test;

import java.util.*;

import static io.edap.protobuf.test.TestUtil.randomStr;
import static org.junit.jupiter.api.Assertions.*;

public class TestMapListCodec {

    @Test
    public void testEncode() throws EncodeException, ProtoException {
        MapListModel model = new MapListModel();

        ProtoBufEncoder<MapListModel> encoder = ProtoBufCodecRegister.INSTANCE.getEncoder(MapListModel.class);
        assertNotNull(encoder);

        Map<Long, List<Project>> map = new HashMap<>();
        int count = new Random().nextInt(10);
        for (int i=0;i<count;i++) {
            List<Project> list = new ArrayList<>();
            int size = new Random().nextInt(20);
            for (int j=0;j<size;j++) {
                list.add(buildProject());
            }
            map.put(new Random().nextLong(), list);
        }
        long pk = new Random().nextLong();
        model.setPk(pk);
        model.setMapList(map);

        byte[] data = ProtoBuf.ser(model);
        assertTrue(data.length > 0);

        MapListModel nModel = (MapListModel) ProtoBuf.der(data);
        assertNotNull(nModel);

        assertEquals(model.getPk(), nModel.getPk());
        assertEquals(model.getMapList().size(), nModel.getMapList().size());
        for (Map.Entry<Long, List<Project>> entry : model.getMapList().entrySet()) {
            List<Project> projs = entry.getValue();
            assertTrue(nModel.getMapList().containsKey(entry.getKey()));
            List<Project> nprojs = nModel.getMapList().get(entry.getKey());
            assertEquals(projs.size(), nprojs.size());
            for (int i=0;i<projs.size();i++) {
                assertTrue(projectEquals(projs.get(i), nprojs.get(i)));
            }
        }
    }

    @Test
    public void testBoolKey() throws EncodeException, ProtoException {
        MapBoolValModel model = new MapBoolValModel();
        model.setPk(new Random().nextLong());
        byte[] data = ProtoBuf.ser(model);
        MapBoolValModel nModel = (MapBoolValModel)ProtoBuf.der(data);
        assertNotNull(nModel);
        assertEquals(nModel.getPk(), model.getPk());

        Map<String, Byte> shortKeymap = buildByteValMap();
        model.setBoolKey(shortKeymap);
        data = ProtoBuf.ser(model);
        nModel = (MapBoolValModel)ProtoBuf.der(data);
        assertNotNull(nModel);
        assertEquals(nModel.getPk(), model.getPk());
        byteValEquals(nModel.getBoolKey(), model.getBoolKey());
    }

    @Test
    public void testMapKeyAllType() throws EncodeException, ProtoException {
        MapAllTypeModel model = new MapAllTypeModel();
        model.setPk(new Random().nextLong());

        byte[] data = ProtoBuf.ser(model);
        MapAllTypeModel nModel = (MapAllTypeModel)ProtoBuf.der(data);
        assertNotNull(nModel);
        assertEquals(nModel.getPk(), model.getPk());

        Map<Byte, Project> byteKeyMap = buildByteKeyMap();
        model.setByteKey(byteKeyMap);
        data = ProtoBuf.ser(model);
        nModel = (MapAllTypeModel)ProtoBuf.der(data);
        assertNotNull(nModel);
        assertEquals(nModel.getPk(), model.getPk());
        byteKeyEquals(nModel.getByteKey(), model.getByteKey());

        Map<Boolean, Project> boolKeymap = buildBoolKeyMap();
        model.setBoolKey(boolKeymap);
        data = ProtoBuf.ser(model);
        nModel = (MapAllTypeModel)ProtoBuf.der(data);
        assertNotNull(nModel);
        assertEquals(nModel.getPk(), model.getPk());
        byteKeyEquals(nModel.getByteKey(), model.getByteKey());
        boolKeyEquals(nModel.getBoolKey(), model.getBoolKey());

        Map<Short, Project> shortKeymap = buildShortKeyMap();
        model.setShortKey(shortKeymap);
        data = ProtoBuf.ser(model);
        nModel = (MapAllTypeModel)ProtoBuf.der(data);
        assertNotNull(nModel);
        assertEquals(nModel.getPk(), model.getPk());
        byteKeyEquals(nModel.getByteKey(), model.getByteKey());
        boolKeyEquals(nModel.getBoolKey(), model.getBoolKey());
        shortKeyEquals(nModel.getShortKey(), model.getShortKey());

        Map<Character, Project> charKeymap = buildCharacterKeyMap();
        model.setCharKey(charKeymap);
        data = ProtoBuf.ser(model);
        nModel = (MapAllTypeModel)ProtoBuf.der(data);
        assertNotNull(nModel);
        assertEquals(nModel.getPk(), model.getPk());
        byteKeyEquals(nModel.getByteKey(), model.getByteKey());
        boolKeyEquals(nModel.getBoolKey(), model.getBoolKey());
        shortKeyEquals(nModel.getShortKey(), model.getShortKey());
        characterKeyEquals(nModel.getCharKey(), model.getCharKey());

        Map<Integer, Project> intKeymap = buildIntKeyMap();
        model.setIntKey(intKeymap);
        data = ProtoBuf.ser(model);
        nModel = (MapAllTypeModel)ProtoBuf.der(data);
        assertNotNull(nModel);
        assertEquals(nModel.getPk(), model.getPk());
        byteKeyEquals(nModel.getByteKey(), model.getByteKey());
        boolKeyEquals(nModel.getBoolKey(), model.getBoolKey());
        shortKeyEquals(nModel.getShortKey(), model.getShortKey());
        characterKeyEquals(nModel.getCharKey(), model.getCharKey());
        intKeyEquals(nModel.getIntKey(), model.getIntKey());

        Map<Float, Project> floatKeymap = buildFloatKeyMap();
        model.setFloatKey(floatKeymap);
        data = ProtoBuf.ser(model);
        nModel = (MapAllTypeModel)ProtoBuf.der(data);
        assertNotNull(nModel);
        assertEquals(nModel.getPk(), model.getPk());
        byteKeyEquals(nModel.getByteKey(), model.getByteKey());
        boolKeyEquals(nModel.getBoolKey(), model.getBoolKey());
        shortKeyEquals(nModel.getShortKey(), model.getShortKey());
        characterKeyEquals(nModel.getCharKey(), model.getCharKey());
        intKeyEquals(nModel.getIntKey(), model.getIntKey());
        floatKeyEquals(nModel.getFloatKey(), model.getFloatKey());

        Map<Long, Project> longKeymap = buildLongKeyMap();
        model.setLongKey(longKeymap);
        data = ProtoBuf.ser(model);
        nModel = (MapAllTypeModel)ProtoBuf.der(data);
        assertNotNull(nModel);
        assertEquals(nModel.getPk(), model.getPk());
        byteKeyEquals(nModel.getByteKey(), model.getByteKey());
        boolKeyEquals(nModel.getBoolKey(), model.getBoolKey());
        shortKeyEquals(nModel.getShortKey(), model.getShortKey());
        characterKeyEquals(nModel.getCharKey(), model.getCharKey());
        intKeyEquals(nModel.getIntKey(), model.getIntKey());
        floatKeyEquals(nModel.getFloatKey(), model.getFloatKey());
        longKeyEquals(nModel.getLongKey(), model.getLongKey());

        Map<Double, Project> doubleKeymap = buildDoubleKeyMap();
        model.setDoubleKey(doubleKeymap);
        data = ProtoBuf.ser(model);
        nModel = (MapAllTypeModel)ProtoBuf.der(data);
        assertNotNull(nModel);
        assertEquals(nModel.getPk(), model.getPk());
        byteKeyEquals(nModel.getByteKey(), model.getByteKey());
        boolKeyEquals(nModel.getBoolKey(), model.getBoolKey());
        shortKeyEquals(nModel.getShortKey(), model.getShortKey());
        characterKeyEquals(nModel.getCharKey(), model.getCharKey());
        intKeyEquals(nModel.getIntKey(), model.getIntKey());
        floatKeyEquals(nModel.getFloatKey(), model.getFloatKey());
        longKeyEquals(nModel.getLongKey(), model.getLongKey());
        doubleKeyEquals(nModel.getDoubleKey(), model.getDoubleKey());
    }

    @Test
    public void testMapValueAllType() throws EncodeException, ProtoException {
        MapAllTypeModel model = new MapAllTypeModel();
        model.setPk(new Random().nextLong());

        byte[] data = ProtoBuf.ser(model);
        MapAllTypeModel nModel = (MapAllTypeModel) ProtoBuf.der(data);
        assertNotNull(nModel);
        assertEquals(nModel.getPk(), model.getPk());

        Map<String, Byte> byteKeyMap = buildByteValMap();
        model.setByteVal(byteKeyMap);
        data = ProtoBuf.ser(model);
        nModel = (MapAllTypeModel)ProtoBuf.der(data);
        assertNotNull(nModel);
        assertEquals(nModel.getPk(), model.getPk());
        byteValEquals(nModel.getByteVal(), model.getByteVal());

        Map<String, Boolean> boolValMap = buildBoolValMap();
        model.setBoolVal(boolValMap);
        data = ProtoBuf.ser(model);
        nModel = (MapAllTypeModel)ProtoBuf.der(data);
        assertNotNull(nModel);
        assertEquals(nModel.getPk(), model.getPk());
        byteValEquals(nModel.getByteVal(), model.getByteVal());
        boolValEquals(nModel.getBoolVal(), model.getBoolVal());

        Map<String, Character> charCharMap = buildCharacterValMap();
        model.setCharVal(charCharMap);
        data = ProtoBuf.ser(model);
        nModel = (MapAllTypeModel)ProtoBuf.der(data);
        assertNotNull(nModel);
        assertEquals(nModel.getPk(), model.getPk());
        byteValEquals(nModel.getByteVal(), model.getByteVal());
        boolValEquals(nModel.getBoolVal(), model.getBoolVal());
        charValEquals(nModel.getCharVal(), model.getCharVal());

        Map<String, Short> shorValMap = buildShortValMap();
        model.setShortVal(shorValMap);
        data = ProtoBuf.ser(model);
        nModel = (MapAllTypeModel)ProtoBuf.der(data);
        assertNotNull(nModel);
        assertEquals(nModel.getPk(), model.getPk());
        byteValEquals(nModel.getByteVal(), model.getByteVal());
        boolValEquals(nModel.getBoolVal(), model.getBoolVal());
        charValEquals(nModel.getCharVal(), model.getCharVal());
        shortValEquals(nModel.getShortVal(), model.getShortVal());

        Map<String, Integer> intValMap = buildIntValMap();
        model.setIntVal(intValMap);
        data = ProtoBuf.ser(model);
        nModel = (MapAllTypeModel)ProtoBuf.der(data);
        assertNotNull(nModel);
        assertEquals(nModel.getPk(), model.getPk());
        byteValEquals(nModel.getByteVal(), model.getByteVal());
        boolValEquals(nModel.getBoolVal(), model.getBoolVal());
        charValEquals(nModel.getCharVal(), model.getCharVal());
        shortValEquals(nModel.getShortVal(), model.getShortVal());
        intValEquals(nModel.getIntVal(), model.getIntVal());

        Map<String, Float> floatValMap = buildFloatValMap();
        model.setFloatVal(floatValMap);
        data = ProtoBuf.ser(model);
        nModel = (MapAllTypeModel)ProtoBuf.der(data);
        assertNotNull(nModel);
        assertEquals(nModel.getPk(), model.getPk());
        byteValEquals(nModel.getByteVal(), model.getByteVal());
        boolValEquals(nModel.getBoolVal(), model.getBoolVal());
        charValEquals(nModel.getCharVal(), model.getCharVal());
        shortValEquals(nModel.getShortVal(), model.getShortVal());
        intValEquals(nModel.getIntVal(), model.getIntVal());
        floatValEquals(nModel.getFloatVal(), model.getFloatVal());

        Map<String, Long> longValMap = buildLongValMap();
        model.setLongVal(longValMap);
        data = ProtoBuf.ser(model);
        nModel = (MapAllTypeModel)ProtoBuf.der(data);
        assertNotNull(nModel);
        assertEquals(nModel.getPk(), model.getPk());
        byteValEquals(nModel.getByteVal(), model.getByteVal());
        boolValEquals(nModel.getBoolVal(), model.getBoolVal());
        charValEquals(nModel.getCharVal(), model.getCharVal());
        shortValEquals(nModel.getShortVal(), model.getShortVal());
        intValEquals(nModel.getIntVal(), model.getIntVal());
        floatValEquals(nModel.getFloatVal(), model.getFloatVal());
        longValEquals(nModel.getLongVal(), model.getLongVal());

        Map<String, Double> doubleValMap = buildDoubleValMap();
        model.setDoubleVal(doubleValMap);
        data = ProtoBuf.ser(model);
        nModel = (MapAllTypeModel)ProtoBuf.der(data);
        assertNotNull(nModel);
        assertEquals(nModel.getPk(), model.getPk());
        byteValEquals(nModel.getByteVal(), model.getByteVal());
        boolValEquals(nModel.getBoolVal(), model.getBoolVal());
        charValEquals(nModel.getCharVal(), model.getCharVal());
        shortValEquals(nModel.getShortVal(), model.getShortVal());
        intValEquals(nModel.getIntVal(), model.getIntVal());
        floatValEquals(nModel.getFloatVal(), model.getFloatVal());
        longValEquals(nModel.getLongVal(), model.getLongVal());
        doubleValEquals(nModel.getDoubleVal(), model.getDoubleVal());
    }


    private Map<Byte, Project> buildByteKeyMap() {
        Map<Byte, Project> map = new HashMap<>();
        int count = 5;
        Random random = new Random();
        for (int i=0;i<count;i++) {
            map.put((byte)(random.nextInt(Byte.MAX_VALUE) - Byte.MAX_VALUE), buildProject());
        }
        map.put((byte)(0), buildProject());
        for (int i=0;i<count;i++) {
            map.put((byte)(random.nextInt(Byte.MAX_VALUE)), buildProject());
        }
        return map;
    }

    private Map<String, Byte> buildByteValMap() {
        Map<String, Byte> map = new HashMap<>();
        int count = 5;
        Random random = new Random();
        for (int i=0;i<count;i++) {
            map.put(randomStr(random.nextInt(15)), (byte)(random.nextInt(Byte.MAX_VALUE) - Byte.MAX_VALUE));
        }
        map.put(randomStr(random.nextInt(15)), (byte)(0));
        for (int i=0;i<count;i++) {
            map.put(randomStr(random.nextInt(15)), (byte)(random.nextInt(Byte.MAX_VALUE)));
        }
        return map;
    }

    private Map<String, Boolean> buildBoolValMap() {
        Map<String, Boolean> map = new HashMap<>();
        int count = 5;
        Random random = new Random();
        for (int i=0;i<count;i++) {
            map.put(randomStr(random.nextInt(15)), random.nextBoolean());
        }
        map.put(randomStr(random.nextInt(15)), false);
        for (int i=0;i<count;i++) {
            map.put(randomStr(random.nextInt(15)), random.nextBoolean());
        }
        return map;
    }

    private Map<Short, Project> buildShortKeyMap() {
        Map<Short, Project> map = new HashMap<>();
        int count = 5;
        Random random = new Random();
        for (int i=0;i<count;i++) {
            map.put((short)(random.nextInt(Short.MAX_VALUE) - Short.MAX_VALUE), buildProject());
        }
        for (int i=0;i<count;i++) {
            map.put((short)(random.nextInt(Short.MAX_VALUE)), buildProject());
        }
        return map;
    }

    private Map<String, Short> buildShortValMap() {
        Map<String, Short> map = new HashMap<>();
        int count = 5;
        Random random = new Random();
        for (int i=0;i<count;i++) {
            int cCount = 1 + random.nextInt(20);
            map.put(randomStr(cCount), (short)(random.nextInt(Short.MAX_VALUE) - Short.MAX_VALUE));
        }
        map.put("", (short)0);
        for (int i=0;i<count;i++) {
            int cCount = 1 + random.nextInt(20);
            map.put(randomStr(cCount), (short)(random.nextInt(Short.MAX_VALUE)));
        }
        return map;
    }

    private Map<String, Integer> buildIntValMap() {
        Map<String, Integer> map = new HashMap<>();
        int count = 5;
        Random random = new Random();
        for (int i=0;i<count;i++) {
            int cCount = 1 + random.nextInt(20);
            map.put(randomStr(cCount), (random.nextInt(Integer.MAX_VALUE) - Integer.MAX_VALUE));
        }
        map.put("", 0);
        for (int i=0;i<count;i++) {
            int cCount = 1 + random.nextInt(20);
            map.put(randomStr(cCount), (random.nextInt(Integer.MAX_VALUE)));
        }
        return map;
    }

    private Map<String, Float> buildFloatValMap() {
        Map<String, Float> map = new HashMap<>();
        int count = 5;
        Random random = new Random();
        for (int i=0;i<count;i++) {
            int cCount = 1 + random.nextInt(20);
            map.put(randomStr(cCount), (float)(random.nextFloat() - Float.MAX_VALUE));
        }
        map.put("", 0F);
        for (int i=0;i<count;i++) {
            int cCount = 1 + random.nextInt(20);
            map.put(randomStr(cCount), (random.nextFloat()));
        }
        return map;
    }

    private Map<String, Long> buildLongValMap() {
        Map<String, Long> map = new HashMap<>();
        int count = 5;
        Random random = new Random();
        for (int i=0;i<count;i++) {
            int cCount = 1 + random.nextInt(20);
            map.put(randomStr(cCount), (random.nextLong() - Long.MAX_VALUE));
        }
        map.put("", 0L);
        for (int i=0;i<count;i++) {
            int cCount = 1 + random.nextInt(20);
            map.put(randomStr(cCount), (random.nextLong()));
        }
        return map;
    }

    private Map<String, Double> buildDoubleValMap() {
        Map<String, Double> map = new HashMap<>();
        int count = 5;
        Random random = new Random();
        for (int i=0;i<count;i++) {
            int cCount = 1 + random.nextInt(20);
            map.put(randomStr(cCount), (random.nextDouble() - Double.MAX_VALUE));
        }
        map.put("", 0D);
        for (int i=0;i<count;i++) {
            int cCount = 1 + random.nextInt(20);
            map.put(randomStr(cCount), (random.nextDouble()));
        }
        return map;
    }

    private Map<Boolean, Project> buildBoolKeyMap() {
        Map<Boolean, Project> map = new HashMap<>();
        map.put(true, buildProject());
        map.put(false, buildProject());
        return map;
    }

    private Map<Character, Project> buildCharacterKeyMap() {
        Map<Character, Project> map = new HashMap<>();
        int count = 5;
        Random random = new Random();
        for (int i=0;i<count;i++) {
            map.put((char)(random.nextInt(Character.MAX_VALUE) - Character.MAX_VALUE), buildProject());
        }
        map.put((char)(0), buildProject());
        for (int i=0;i<count;i++) {
            map.put((char)(random.nextInt(Character.MAX_VALUE)), buildProject());
        }
        return map;
    }

    private Map<String, Character> buildCharacterValMap() {
        Map<String, Character> map = new HashMap<>();
        int count = 5;
        Random random = new Random();
        for (int i=0;i<count;i++) {
            int cCount = 1 + random.nextInt(20);
            map.put(randomStr(cCount), (char)(random.nextInt(Character.MAX_VALUE) - Character.MAX_VALUE));
        }
        map.put("", (char)0);
        for (int i=0;i<count;i++) {
            int cCount = 2 + random.nextInt(20);
            map.put(randomStr(cCount), (char)(random.nextInt(Character.MAX_VALUE) - Character.MAX_VALUE));
        }
        return map;
    }

    private Map<Integer, Project> buildIntKeyMap() {
        Map<Integer, Project> map = new HashMap<>();
        int count = 5;
        Random random = new Random();
        for (int i=0;i<count;i++) {
            map.put((random.nextInt(Character.MAX_VALUE) - Character.MAX_VALUE), buildProject());
        }
        map.put((0), buildProject());
        for (int i=0;i<count;i++) {
            map.put((random.nextInt(Character.MAX_VALUE)), buildProject());
        }
        return map;
    }

    private Map<Long, Project> buildLongKeyMap() {
        Map<Long, Project> map = new HashMap<>();
        int count = 5;
        Random random = new Random();
        for (int i=0;i<count;i++) {
            map.put((random.nextLong() - Long.MAX_VALUE), buildProject());
        }
        map.put((0L), buildProject());
        for (int i=0;i<count;i++) {
            map.put((random.nextLong()), buildProject());
        }
        return map;
    }

    private Map<Float, Project> buildFloatKeyMap() {
        Map<Float, Project> map = new HashMap<>();
        int count = 5;
        Random random = new Random();
        for (int i=0;i<count;i++) {
            map.put((random.nextFloat() - Float.MAX_VALUE), buildProject());
        }
        map.put((0F), buildProject());
        for (int i=0;i<count;i++) {
            map.put((random.nextFloat()), buildProject());
        }
        return map;
    }

    private Map<Double, Project> buildDoubleKeyMap() {
        Map<Double, Project> map = new HashMap<>();
        int count = 5;
        Random random = new Random();
        for (int i=0;i<count;i++) {
            map.put((random.nextDouble() - Double.MAX_VALUE), buildProject());
        }
        map.put((0D), buildProject());
        for (int i=0;i<count;i++) {
            map.put((random.nextDouble()), buildProject());
        }
        return map;
    }

    private void byteKeyEquals(Map<Byte, Project> one, Map<Byte, Project> other) {
        for (Map.Entry<Byte, Project> entry : one.entrySet()) {
            assertTrue(other.containsKey(entry.getKey()));
            projectEquals(entry.getValue(), other.get(entry.getKey()));
        }
    }

    private void byteValEquals(Map<String, Byte> one, Map<String, Byte> other) {
        for (Map.Entry<String, Byte> entry : one.entrySet()) {
            assertTrue(other.containsKey(entry.getKey()));
            assertEquals(entry.getValue(), other.get(entry.getKey()));
        }
    }

    private void boolValEquals(Map<String, Boolean> one, Map<String, Boolean> other) {
        for (Map.Entry<String, Boolean> entry : one.entrySet()) {
            assertTrue(other.containsKey(entry.getKey()));
            assertEquals(entry.getValue(), other.get(entry.getKey()));
        }
    }

    private void intValEquals(Map<String, Integer> one, Map<String, Integer> other) {
        for (Map.Entry<String, Integer> entry : one.entrySet()) {
            assertTrue(other.containsKey(entry.getKey()));
            assertEquals(entry.getValue(), other.get(entry.getKey()));
        }
    }

    private void longValEquals(Map<String, Long> one, Map<String, Long> other) {
        for (Map.Entry<String, Long> entry : one.entrySet()) {
            assertTrue(other.containsKey(entry.getKey()));
            assertEquals(entry.getValue(), other.get(entry.getKey()));
        }
    }

    private void doubleValEquals(Map<String, Double> one, Map<String, Double> other) {
        for (Map.Entry<String, Double> entry : one.entrySet()) {
            assertTrue(other.containsKey(entry.getKey()));
            assertEquals(entry.getValue(), other.get(entry.getKey()));
        }
    }

    private void boolKeyEquals(Map<Boolean, Project> one, Map<Boolean, Project> other) {
        for (Map.Entry<Boolean, Project> entry : one.entrySet()) {
            assertTrue(other.containsKey(entry.getKey()));
            projectEquals(entry.getValue(), other.get(entry.getKey()));
        }
    }

    private void shortKeyEquals(Map<Short, Project> one, Map<Short, Project> other) {
        for (Map.Entry<Short, Project> entry : one.entrySet()) {
            assertTrue(other.containsKey(entry.getKey()));
            projectEquals(entry.getValue(), other.get(entry.getKey()));
        }
    }

    private void characterKeyEquals(Map<Character, Project> one, Map<Character, Project> other) {
        for (Map.Entry<Character, Project> entry : one.entrySet()) {
            assertTrue(other.containsKey(entry.getKey()));
            projectEquals(entry.getValue(), other.get(entry.getKey()));
        }
    }

    private void charValEquals(Map<String, Character> one, Map<String, Character> other) {
        for (Map.Entry<String, Character> entry : one.entrySet()) {
            assertTrue(other.containsKey(entry.getKey()));
            assertEquals(entry.getValue(), other.get(entry.getKey()));
        }
    }

    private void shortValEquals(Map<String, Short> one, Map<String, Short> other) {
        for (Map.Entry<String, Short> entry : one.entrySet()) {
            assertTrue(other.containsKey(entry.getKey()));
            assertEquals(entry.getValue(), other.get(entry.getKey()));
        }
    }

    private void intKeyEquals(Map<Integer, Project> one, Map<Integer, Project> other) {
        for (Map.Entry<Integer, Project> entry : one.entrySet()) {
            assertTrue(other.containsKey(entry.getKey()));
            projectEquals(entry.getValue(), other.get(entry.getKey()));
        }
    }

    private void floatValEquals(Map<String, Float> one, Map<String, Float> other) {
        for (Map.Entry<String, Float> entry : one.entrySet()) {
            assertTrue(other.containsKey(entry.getKey()));
            assertEquals(entry.getValue(), other.get(entry.getKey()));
        }
    }

    private void longKeyEquals(Map<Long, Project> one, Map<Long, Project> other) {
        for (Map.Entry<Long, Project> entry : one.entrySet()) {
            assertTrue(other.containsKey(entry.getKey()));
            projectEquals(entry.getValue(), other.get(entry.getKey()));
        }
    }

    private void floatKeyEquals(Map<Float, Project> one, Map<Float, Project> other) {
        for (Map.Entry<Float, Project> entry : one.entrySet()) {
            assertTrue(other.containsKey(entry.getKey()));
            projectEquals(entry.getValue(), other.get(entry.getKey()));
        }
    }

    private void doubleKeyEquals(Map<Double, Project> one, Map<Double, Project> other) {
        for (Map.Entry<Double, Project> entry : one.entrySet()) {
            assertTrue(other.containsKey(entry.getKey()));
            projectEquals(entry.getValue(), other.get(entry.getKey()));
        }
    }

    private boolean projectEquals(Project old, Project other) {
        if (old.getId().longValue() != other.getId().longValue()) {
            return false;
        }
        if (!old.getName().equals(other.getName())) {
            return false;
        }
        if (!old.getRepoPath().equals(other.getRepoPath())) {
            return false;
        }
        return true;
    }

    private Project buildProject() {
        long id = new Random().nextLong();
        String name = randomStr(new Random().nextInt(200));
        Project proj = new Project();
        proj.setId(id);
        proj.setName(name);
        proj.setRepoPath("http://" + randomStr(new Random().nextInt(20)) + "/" + name);
        return proj;
    }
}
