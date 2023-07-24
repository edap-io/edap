package io.edap.protobuf.test;

import io.edap.protobuf.ProtoBuf;
import io.edap.protobuf.ProtoBufCodecRegister;
import io.edap.protobuf.ProtoBufDecoder;
import io.edap.protobuf.ProtoBufException;
import io.edap.protobuf.model.ProtoBufOption;
import io.edap.protobuf.reader.ByteArrayFastReader;
import io.edap.protobuf.test.message.v3.Project;
import io.edap.protobuf.wire.Field;
import io.edap.protobuf.wire.Message;
import io.edap.protobuf.wire.Proto;
import io.edap.protobuf.wire.exceptions.ProtoParseException;
import io.edap.protobuf.wire.parser.ProtoParser;
import org.junit.jupiter.api.Test;

import static io.edap.protobuf.test.TestUtil.conver2HexStr;
import static io.edap.protobuf.wire.WireFormat.makeTag;
import static io.edap.protobuf.wire.WireType.END_GROUP;
import static org.junit.jupiter.api.Assertions.*;

public class TestByteArrayFastReader {

    @Test
    public void testByteArrayFastReader() throws ProtoBufException {

        ProtoBufOption option = new ProtoBufOption();
        option.setCodecType(ProtoBuf.CodecType.FAST);

        Project project = new Project();
        project.setId(1L);
        project.setName("edap");
        project.setRepoPath("https://www.easyea.com/edap/edap.git");
        byte[] epb = ProtoBuf.toByteArray(project, option);

        System.out.println("+-epbf[" + epb.length + "]-------------------+");
        System.out.println(conver2HexStr(epb));
        System.out.println("+--------------------+");

        ProtoBufDecoder decoder = ProtoBufCodecRegister.INSTANCE.getDecoder(Project.class, option);
        ByteArrayFastReader reader = new ByteArrayFastReader(epb);
        Project proj = (Project)reader.readMessage(decoder, makeTag(1, END_GROUP));
        assertNotNull(proj);
        assertEquals(proj.getId(), project.getId());
        assertEquals(proj.getName(), project.getName());
        assertEquals(proj.getRepoPath(), project.getRepoPath());
    }

    @Test
    public void testReadPackedInt64Array() {
        ProtoBufOption option = new ProtoBufOption();
        option.setCodecType(ProtoBuf.CodecType.FAST);

        Project project = new Project();
        project.setId(1L);
        project.setName("edap");
        project.setRepoPath("https://www.easyea.com/edap/edap.git");
        byte[] epb = ProtoBuf.toByteArray(project, option);

        ByteArrayFastReader reader = new ByteArrayFastReader(epb);

        ProtoBufException thrown = assertThrows(ProtoBufException.class,
                () -> {
                    reader.readPackedInt64Array(Field.Type.INT32);
                });
        assertNotNull(thrown);
        assertTrue(thrown.getMessage().contains("CodedInputStream encountered a malformed varint."));
    }

    @Test
    public void testReadPackedInt64() {
        ProtoBufOption option = new ProtoBufOption();
        option.setCodecType(ProtoBuf.CodecType.FAST);

        Project project = new Project();
        project.setId(1L);
        project.setName("edap");
        project.setRepoPath("https://www.easyea.com/edap/edap.git");
        byte[] epb = ProtoBuf.toByteArray(project, option);

        ByteArrayFastReader reader = new ByteArrayFastReader(epb);

        ProtoBufException thrown = assertThrows(ProtoBufException.class,
                () -> {
                    reader.readPackedInt64(Field.Type.INT32);
                });
        assertNotNull(thrown);
        assertTrue(thrown.getMessage().contains("CodedInputStream encountered a malformed varint."));
    }

    @Test
    public void testReadPackedInt32() {
        ProtoBufOption option = new ProtoBufOption();
        option.setCodecType(ProtoBuf.CodecType.FAST);

        Project project = new Project();
        project.setId(1L);
        project.setName("edap");
        project.setRepoPath("https://www.easyea.com/edap/edap.git");
        byte[] epb = ProtoBuf.toByteArray(project, option);

        ByteArrayFastReader reader = new ByteArrayFastReader(epb);
        ProtoBufException thrown = assertThrows(ProtoBufException.class,
                () -> {
                    reader.readPackedInt32(Field.Type.INT64);
                });
        assertNotNull(thrown);
        assertTrue(thrown.getMessage().contains("CodedInputStream encountered a malformed varint."));
    }

    @Test
    public void testReadPackedInt32Array() {
        ProtoBufOption option = new ProtoBufOption();
        option.setCodecType(ProtoBuf.CodecType.FAST);

        Project project = new Project();
        project.setId(1L);
        project.setName("edap");
        project.setRepoPath("https://www.easyea.com/edap/edap.git");
        byte[] epb = ProtoBuf.toByteArray(project, option);

        ByteArrayFastReader reader = new ByteArrayFastReader(epb);
        ProtoBufException thrown = assertThrows(ProtoBufException.class,
                () -> {
                    reader.readPackedInt32Array(Field.Type.INT64);
                });
        assertNotNull(thrown);
        assertTrue(thrown.getMessage().contains("CodedInputStream encountered a malformed varint."));
    }

    @Test
    public void testReadPackedInt32ArrayValue() {
        ProtoBufOption option = new ProtoBufOption();
        option.setCodecType(ProtoBuf.CodecType.FAST);

        Project project = new Project();
        project.setId(1L);
        project.setName("edap");
        project.setRepoPath("https://www.easyea.com/edap/edap.git");
        byte[] epb = ProtoBuf.toByteArray(project, option);

        ByteArrayFastReader reader = new ByteArrayFastReader(epb);
        ProtoBufException thrown = assertThrows(ProtoBufException.class,
                () -> {
                    reader.readPackedInt32ArrayValue(Field.Type.INT64);
                });
        assertNotNull(thrown);
        assertTrue(thrown.getMessage().contains("CodedInputStream encountered a malformed varint."));
    }
}
