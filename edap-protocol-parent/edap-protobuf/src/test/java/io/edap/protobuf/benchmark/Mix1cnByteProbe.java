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

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * 诊断：标准版编码器给 mix-1cn 写出的 UTF-8 字节，是否等于 {@code s.getBytes(UTF_8)}？
 * 以及标准读端解码它时，是否触发了 {@code decodeUtf8Strict}（含 U+FFFD ⇒ 触发）。
 */
public final class Mix1cnByteProbe {

    private Mix1cnByteProbe() {
    }

    public static void main(String[] args) {
        for (String id : new String[]{"mix-1cn", "mix-cn10", "mix-cn50", "ascii-16k"}) {
            BenchPayloads.Payload p = BenchPayloads.of(id);
            String s = ((BenchStr) p.obj).getS();

            byte[] wire = ProtoBuf.toByteArray(p.obj);
            byte[] expect = s.getBytes(StandardCharsets.UTF_8);

            // wire 布局：tag(1) + varint 长度 + payload
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
            byte[] payload = Arrays.copyOfRange(wire, pos, pos + len);

            boolean same = Arrays.equals(payload, expect);
            // 解码后的串里有没有 U+FFFD
            String decoded = new String(payload, StandardCharsets.UTF_8);
            boolean hasFffd = decoded.indexOf('�') >= 0;

            System.out.printf("%-12s wireLen=%d varintLen=%d payloadLen=%d expectLen=%d same=%s hasFFFD=%s%n",
                    id, wire.length, pos - 1, payload.length, expect.length, same, hasFffd);
            if (!same) {
                // 找第一处不同
                int n = Math.min(payload.length, expect.length);
                for (int i = 0; i < n; i++) {
                    if (payload[i] != expect[i]) {
                        System.out.printf("   ★ 第 %d 字节不同: wire=%02x expect=%02x%n", i, payload[i], expect[i]);
                        break;
                    }
                }
                // 打印尾部
                System.out.printf("   wire 尾部  : %s%n", hex(payload, Math.max(0, payload.length - 12), payload.length));
                System.out.printf("   expect 尾部: %s%n", hex(expect, Math.max(0, expect.length - 12), expect.length));
                // 有没有 3 字节的 UTF-8 序列被截断
                System.out.printf("   wire 里有 U+FFFD 三字节序列 EF BF BD ? %s%n",
                        indexOf(payload, new byte[]{(byte) 0xEF, (byte) 0xBF, (byte) 0xBD}) >= 0);
            }
        }
    }

    private static int indexOf(byte[] a, byte[] pat) {
        outer:
        for (int i = 0; i + pat.length <= a.length; i++) {
            for (int j = 0; j < pat.length; j++) {
                if (a[i + j] != pat[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    private static String hex(byte[] b, int from, int to) {
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < to; i++) {
            sb.append(String.format("%02x ", b[i]));
        }
        return sb.toString();
    }
}
