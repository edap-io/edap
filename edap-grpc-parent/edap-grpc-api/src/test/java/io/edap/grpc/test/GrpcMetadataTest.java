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

package io.edap.grpc.test;

import io.edap.grpc.GrpcMetadata;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class GrpcMetadataTest {

    @Test
    public void testKeyIsLowerCase() {
        GrpcMetadata metadata = new GrpcMetadata();
        metadata.add("Content-Type", "application/grpc");
        assertEquals("application/grpc", metadata.get("content-type"));
        assertEquals("application/grpc", metadata.get("CONTENT-TYPE"));
        assertTrue(metadata.keys().contains("content-type"));
    }

    @Test
    public void testMultiValue() {
        GrpcMetadata metadata = new GrpcMetadata();
        metadata.add("key", "v1");
        metadata.add("key", "v2");
        assertEquals("v1", metadata.get("key"));
        assertEquals(Arrays.asList("v1", "v2"), metadata.getAll("key"));
        assertEquals(1, metadata.size());
    }

    @Test
    public void testSetOverwrites() {
        GrpcMetadata metadata = new GrpcMetadata();
        metadata.add("key", "v1");
        metadata.set("key", "v2");
        assertEquals("v2", metadata.get("key"));
        assertEquals(1, metadata.getAll("key").size());
    }

    @Test
    public void testRemove() {
        GrpcMetadata metadata = new GrpcMetadata();
        metadata.add("key", "v1");
        metadata.remove("KEY");
        assertNull(metadata.get("key"));
        assertTrue(metadata.isEmpty());
    }

    @Test
    public void testAbsentKey() {
        GrpcMetadata metadata = new GrpcMetadata();
        assertNull(metadata.get("none"));
        assertTrue(metadata.getAll("none").isEmpty());
        assertFalse(metadata.contains("none"));
    }

    @Test
    public void testConstructFromMap() {
        Map<String, List<String>> source = new HashMap<>();
        source.put("a", Arrays.asList("1", "2"));
        source.put("b", null);
        GrpcMetadata metadata = new GrpcMetadata(source);
        assertEquals("1", metadata.get("a"));
        assertEquals(2, metadata.getAll("a").size());
        assertEquals(1, metadata.size());
    }

    @Test
    public void testNullValueIgnored() {
        GrpcMetadata metadata = new GrpcMetadata();
        metadata.add("key", null);
        assertTrue(metadata.isEmpty());
    }
}
