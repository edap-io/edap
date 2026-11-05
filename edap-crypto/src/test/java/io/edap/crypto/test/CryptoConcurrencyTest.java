/*
 * Copyright 2023 The edap Project
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

package io.edap.crypto.test;

import io.edap.crypto.CryptoRegistry;
import io.edap.crypto.digest.DigestProvider;
import io.edap.crypto.digest.DigestSession;
import io.edap.crypto.digest.JceDigestProvider;
import io.edap.crypto.mac.JceMacProvider;
import io.edap.crypto.mac.MacProvider;
import io.edap.crypto.mac.MacSession;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 并发一致性 —— provider 是全局单例语义、session 是线程绑定单位，
 * 16 个线程同时用同一个 provider 必须拿到与单线程一致的结果。
 */
public class CryptoConcurrencyTest {

    private static final int THREADS = 16;

    private static final int ITERATIONS = 1000;

    @Test
    @DisplayName("16 线程并发摘要 —— 结果与单线程一致")
    public void testConcurrentDigest() throws Exception {
        byte[] data = new byte[4096];
        for (int i = 0; i < data.length; i++) {
            data[i] = (byte) (i * 17);
        }
        DigestProvider provider = new JceDigestProvider("SHA-256");

        DigestSession single = provider.newSession();
        single.update(data);
        byte[] expected = single.digest();

        AtomicReference<Throwable> failure = new AtomicReference<>();
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(THREADS);
        List<byte[]> results = new ArrayList<>();
        for (int t = 0; t < THREADS; t++) {
            pool.execute(() -> {
                try {
                    start.await();
                    byte[] last = null;
                    for (int i = 0; i < ITERATIONS; i++) {
                        DigestSession session = provider.newSession();
                        session.update(data);
                        last = session.digest();
                    }
                    synchronized (results) {
                        results.add(last);
                    }
                } catch (Throwable e) {
                    failure.compareAndSet(null, e);
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(60, TimeUnit.SECONDS), "并发测试超时");
        pool.shutdownNow();

        assertNull(failure.get(), () -> "并发执行失败: " + failure.get());
        assertEquals(THREADS, results.size());
        for (byte[] r : results) {
            assertArrayEquals(expected, r);
        }
    }

    @Test
    @DisplayName("16 线程并发 MAC —— 结果与单线程一致")
    public void testConcurrentMac() throws Exception {
        byte[] key = "0123456789abcdef0123456789abcdef".getBytes("UTF-8");
        byte[] data = "concurrent mac payload".getBytes("UTF-8");
        MacProvider provider = new JceMacProvider("HmacSHA256");

        MacSession single = provider.newSession(key);
        single.update(data);
        byte[] expected = single.doFinal();

        AtomicReference<Throwable> failure = new AtomicReference<>();
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(THREADS);
        List<byte[]> results = new ArrayList<>();
        for (int t = 0; t < THREADS; t++) {
            pool.execute(() -> {
                try {
                    start.await();
                    byte[] last = null;
                    for (int i = 0; i < ITERATIONS; i++) {
                        MacSession session = provider.newSession(key);
                        session.update(data);
                        last = session.doFinal();
                    }
                    synchronized (results) {
                        results.add(last);
                    }
                } catch (Throwable e) {
                    failure.compareAndSet(null, e);
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(60, TimeUnit.SECONDS), "并发测试超时");
        pool.shutdownNow();

        assertNull(failure.get(), () -> "并发执行失败: " + failure.get());
        assertEquals(THREADS, results.size());
        for (byte[] r : results) {
            assertArrayEquals(expected, r);
        }
    }

    @Test
    @DisplayName("16 线程并发查注册表 —— 返回同一个缓存实例")
    public void testConcurrentRegistryLookup() throws Exception {
        DigestProvider provider = new JceDigestProvider("SHA-256");
        CryptoRegistry.registerDigest("SHA-256", provider);

        AtomicReference<Throwable> failure = new AtomicReference<>();
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(THREADS);
        for (int t = 0; t < THREADS; t++) {
            pool.execute(() -> {
                try {
                    start.await();
                    for (int i = 0; i < ITERATIONS; i++) {
                        if (CryptoRegistry.digest("SHA-256") != provider) {
                            throw new AssertionError("注册表返回了不同的实例");
                        }
                    }
                } catch (Throwable e) {
                    failure.compareAndSet(null, e);
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(60, TimeUnit.SECONDS), "并发测试超时");
        pool.shutdownNow();

        assertNull(failure.get(), () -> "并发执行失败: " + failure.get());
        assertSame(provider, CryptoRegistry.digest("SHA-256"));
        CryptoRegistry.resetForTest();
    }
}
