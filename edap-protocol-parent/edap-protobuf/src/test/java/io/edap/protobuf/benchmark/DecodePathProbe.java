/*
 * Copyright 2026 The edap Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package io.edap.protobuf.benchmark;

import io.edap.protobuf.ProtoBuf;
import io.edap.protobuf.reader.ByteArrayReader;

import java.nio.charset.StandardCharsets;

/**
 * 定位：标准版解码路径里，{@code readString} 之外还多花在哪。
 *
 * <p>三个测点：
 * <ol>
 *   <li>整条路径：{@code ProtoBuf.toObject(bs, BenchStr.class)}</li>
 *   <li>只调读端的字符串方法：{@code new ByteArrayReader(bs,0,len).readString()}</li>
 *   <li>裸 JDK 解码：{@code new String(payload, 0, len, UTF_8)}</li>
 * </ol>
 *
 * <p>用法：{@code DecodePathProbe <payloadId>}（一个负载一个 JVM）。</p>
 */
public final class DecodePathProbe {

    private static long sink;

    private DecodePathProbe() {
    }

    public static void main(String[] args) {
        String id = args.length > 0 ? args[0] : "mix-1cn";
        BenchPayloads.Payload p = BenchPayloads.of(id);
        byte[] wire = ProtoBuf.toByteArray(p.obj);

        // 剥出 payload：tag(1) + varint 长度
        int pos = 1;
        int len = 0;
        int shift = 0;
        while (true) {
            int b = wire[pos++] & 0xFF;
            len |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                break;
            }
            shift += 7;
        }
        final byte[] payload = new byte[len];
        System.arraycopy(wire, pos, payload, 0, len);

        System.out.printf("=== %s : wire=%d payload=%d%n", id, wire.length, len);

        double t1 = bench(() -> {
            Object o = ProtoBuf.toObject(wire, BenchStr.class);
            sink += o == null ? 0 : 1;
            return 0;
        });
        double t2 = bench(() -> {
            try {
                // ★ 起点必须是「长度 varint」处 ⇒ 跳过 1 字节字段 tag
                ByteArrayReader r = new ByteArrayReader(wire, 1, wire.length - 1);
                String s = r.readString();
                sink += s == null ? 0 : s.length();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            return 0;
        });
        double t3 = bench(() -> {
            String s = new String(payload, 0, payload.length, StandardCharsets.UTF_8);
            sink += s.length();
            return 0;
        });
        double t4 = bench(() -> {
            String s = new String(payload, 0, payload.length, StandardCharsets.UTF_8);
            sink += s.indexOf('�');
            return 0;
        });

        System.out.printf("  ① ProtoBuf.toObject(整条路径)      = %9.1f ns%n", t1);
        System.out.printf("  ② ByteArrayReader.readString()    = %9.1f ns%n", t2);
        System.out.printf("  ③ new String(payload, UTF_8)       = %9.1f ns%n", t3);
        System.out.printf("  ④ ③ + indexOf('\\uFFFD')            = %9.1f ns%n", t4);
        System.out.printf("  ⇒ ①-② = %9.1f ns（toObject 里除读串以外的额外开销）%n", t1 - t2);
    }

    private static double bench(Task r) {
        for (int i = 0; i < 2000; i++) {
            r.run();
        }
        double best = Double.MAX_VALUE;
        for (int round = 0; round < 5; round++) {
            long deadline = System.nanoTime() + 200_000_000L;
            long ops = 0;
            long start = System.nanoTime();
            while (System.nanoTime() < deadline) {
                for (int i = 0; i < 20; i++) {
                    r.run();
                    ops++;
                }
            }
            long elapsed = System.nanoTime() - start;
            double ns = ops == 0 ? 0 : (double) elapsed / ops;
            if (ns < best) {
                best = ns;
            }
        }
        return best;
    }

    private interface Task {
        long run();
    }
}
