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
    public void testMapAllType() throws EncodeException, ProtoException {
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

    private void intKeyEquals(Map<Integer, Project> one, Map<Integer, Project> other) {
        for (Map.Entry<Integer, Project> entry : one.entrySet()) {
            assertTrue(other.containsKey(entry.getKey()));
            projectEquals(entry.getValue(), other.get(entry.getKey()));
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
