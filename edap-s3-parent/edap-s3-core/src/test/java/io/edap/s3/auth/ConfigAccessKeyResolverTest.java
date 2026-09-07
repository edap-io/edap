/*
 * Copyright 2026 The edap Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package io.edap.s3.auth;

import org.junit.jupiter.api.Test;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ConfigAccessKeyResolverTest {

    @Test
    void loadsSingleKeyWithDefaults() {
        Properties p = new Properties();
        p.setProperty("s3.accessKey.AKIA.secret", "wJalr...");
        AccessKeyResolver r = ConfigAccessKeyResolver.fromProperties(p);
        AccessKeyResolver.ResolvedKey k = r.resolve("AKIA");
        assertNotNull(k);
        assertEquals("wJalr...", k.secretKey());
        assertEquals("us-east-1", k.region());       // default
        assertEquals("s3", k.service());              // hardcoded
        assertNull(k.allowedBuckets());               // not set = unrestricted
    }

    @Test
    void loadsRegionAndBuckets() {
        Properties p = new Properties();
        p.setProperty("s3.accessKey.AKIA.secret", "s");
        p.setProperty("s3.accessKey.AKIA.region", "eu-west-1");
        p.setProperty("s3.accessKey.AKIA.buckets", "foo, bar ,baz");
        AccessKeyResolver.ResolvedKey k = ConfigAccessKeyResolver.fromProperties(p).resolve("AKIA");
        assertEquals("eu-west-1", k.region());
        assertNotNull(k.allowedBuckets());
        assertEquals(3, k.allowedBuckets().size());
        assertTrue(k.allowedBuckets().contains("foo"));
        assertTrue(k.allowedBuckets().contains("bar"));
        assertTrue(k.allowedBuckets().contains("baz"));
    }

    @Test
    void missingSecretThrows() {
        Properties p = new Properties();
        p.setProperty("s3.accessKey.AKIA.region", "us-east-1");
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> ConfigAccessKeyResolver.fromProperties(p));
        assertTrue(ex.getMessage().contains("AKIA"));
        assertTrue(ex.getMessage().contains("secret"));
    }

    @Test
    void unknownKeyReturnsNull() {
        Properties p = new Properties();
        p.setProperty("s3.accessKey.AKIA.secret", "s");
        assertNull(ConfigAccessKeyResolver.fromProperties(p).resolve("UNKNOWN"));
    }

    @Test
    void loadsMultipleKeys() {
        Properties p = new Properties();
        p.setProperty("s3.accessKey.AKIA1.secret", "s1");
        p.setProperty("s3.accessKey.AKIA2.secret", "s2");
        AccessKeyResolver r = ConfigAccessKeyResolver.fromProperties(p);
        assertEquals("s1", r.resolve("AKIA1").secretKey());
        assertEquals("s2", r.resolve("AKIA2").secretKey());
    }

    @Test
    void ignoresUnknownPrefixes() {
        Properties p = new Properties();
        p.setProperty("other.key", "v");
        p.setProperty("s3.accessKey.AKIA.secret", "s");
        assertEquals("s", ConfigAccessKeyResolver.fromProperties(p).resolve("AKIA").secretKey());
    }

    @Test
    void missingFieldSuffixIsIgnored() {
        Properties p = new Properties();
        p.setProperty("s3.accessKey.AKIA", "orphan");   // no .field
        p.setProperty("s3.accessKey.AKIA.secret", "s");
        assertEquals("s", ConfigAccessKeyResolver.fromProperties(p).resolve("AKIA").secretKey());
    }
}