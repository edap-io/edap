/*
 * Copyright 2020 The edap Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package io.edap.protobuf.wire.test;

import io.edap.protobuf.wire.*;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 测试proto对象的操作逻辑
 */
public class TestProto {

    @Test
    void testSetEnums() {
        Proto proto = new Proto();

        proto.setEnums(null);
        ProtoEnum protoEnum = new ProtoEnum();
        List<ProtoEnum> es = Arrays.asList(protoEnum);

        assertFalse(es instanceof ArrayList);
        proto.setEnums(es);

        es = proto.getEnums();
        assertTrue(es instanceof ArrayList);

        proto = new Proto();
        es = proto.getEnums();
        assertTrue(es instanceof ArrayList);
        es.add(protoEnum);

        proto = new Proto();
        proto.setEnums(es);

        es = proto.getEnums();
        assertTrue(es instanceof ArrayList);
    }

    @Test
    void testSetImps() {
        Proto proto = new Proto();
        String imp = "ttt";
        proto.setImports(null);
        List<String> es = Arrays.asList(imp);

        assertFalse(es instanceof ArrayList);
        proto.setImports(es);

        es = proto.getImports();
        assertTrue(es instanceof ArrayList);

        proto = new Proto();
        es = proto.getImports();
        assertTrue(es instanceof ArrayList);
        es.add("");

        proto = new Proto();
        proto.setImports(es);

        es = proto.getImports();
        assertTrue(es instanceof ArrayList);
    }

    @Test
    void testSetComments() {
        Proto proto = new Proto();
        String imp = "ttt";
        proto.setComments(null);
        List<String> es = Arrays.asList(imp);

        assertFalse(es instanceof ArrayList);
        proto.setComments(es);

        es = proto.getComments();
        assertTrue(es instanceof ArrayList);

        proto = new Proto();
        es = proto.getComments();
        assertTrue(es instanceof ArrayList);
        es.add("");

        proto = new Proto();
        proto.setComments(es);

        es = proto.getComments();
        assertTrue(es instanceof ArrayList);
    }

    @Test
    void testSetMsgs() {
        Proto proto = new Proto();

        proto.setMessages(null);
        Message msg = new Message();
        List<Message> es = Arrays.asList(msg);

        assertFalse(es instanceof ArrayList);
        proto.setMessages(es);

        es = proto.getMessages();
        assertTrue(es instanceof ArrayList);

        proto = new Proto();
        es = proto.getMessages();
        assertTrue(es instanceof ArrayList);
        es.add(msg);

        proto = new Proto();
        proto.setMessages(es);

        es = proto.getMessages();
        assertTrue(es instanceof ArrayList);
    }

    @Test
    void testSetServices() {
        Proto proto = new Proto();

        proto.setServices(null);
        Service msg = new Service();
        List<Service> es = Arrays.asList(msg);

        assertFalse(es instanceof ArrayList);
        proto.setServices(es);

        es = proto.getServices();
        assertTrue(es instanceof ArrayList);

        proto = new Proto();
        es = proto.getServices();
        assertTrue(es instanceof ArrayList);
        es.add(msg);

        proto = new Proto();
        proto.setServices(es);

        es = proto.getServices();
        assertTrue(es instanceof ArrayList);
    }

    @Test
    void testSetOptions() {
        Proto proto = new Proto();

        proto.setOptions(null);
        Option msg = new Option();
        List<Option> es = Arrays.asList(msg);

        assertFalse(es instanceof ArrayList);
        proto.setOptions(es);

        es = proto.getOptions();
        assertTrue(es instanceof ArrayList);

        proto = new Proto();
        es = proto.getOptions();
        assertTrue(es instanceof ArrayList);
        es.add(msg);

        proto = new Proto();
        proto.setOptions(es);

        es = proto.getOptions();
        assertTrue(es instanceof ArrayList);
    }

    @Test
    void testSetExtends() {
        Proto proto = new Proto();

        proto.setProtoExtends(null);
        Extend msg = new Extend();
        List<Extend> es = Arrays.asList(msg);

        assertFalse(es instanceof ArrayList);
        proto.setProtoExtends(es);

        es = proto.getProtoExtends();
        assertTrue(es instanceof ArrayList);

        proto = new Proto();
        es = proto.getProtoExtends();
        assertTrue(es instanceof ArrayList);
        es.add(msg);

        proto = new Proto();
        proto.setProtoExtends(es);

        es = proto.getProtoExtends();
        assertTrue(es instanceof ArrayList);
    }

    @Test
    void testAddServices() {
        Proto proto = new Proto();

        proto.addService(null);
        assertEquals(0, proto.getServices().size());
    }

    @Test
    void testAddExtend() {
        Proto proto = new Proto();

        proto.addExtend(null);
        assertEquals(0, proto.getProtoExtends().size());
    }

    @Test
    void testName() {
        Proto proto = new Proto();

        proto.setName("test.proto");

        assertEquals("test.proto", proto.getName());
    }

    @Test
    void testPackage() {
        Proto proto = new Proto();

        proto.setProtoPackage("test");

        assertEquals("test", proto.getProtoPackage());
    }

    @Test
    void testComment() {
        Proto proto = new Proto();

        proto.setComment("test.proto");

        assertEquals("test.proto", proto.getComment());
    }

    @Test
    void testEmpty() {
        Proto proto = new Proto();

        proto.setEmpty(false);

        assertFalse(proto.isEmpty());
    }

    @Test
    void testFile() {
        Proto proto = new Proto();
        File f = new File("test.proto");
        proto.setFile(f);

        assertEquals(proto.getFile(), f);
    }
}