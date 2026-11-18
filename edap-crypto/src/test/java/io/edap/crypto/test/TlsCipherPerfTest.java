/*
 * Copyright 2023 The edap Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package io.edap.crypto.test;

import io.edap.crypto.benchmark.TlsCipherProbe;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>TLS 对称加密吞吐的测量入口</b>（跑 {@link TlsCipherProbe} 的父进程模式）。
 *
 * <p><b>它回答的问题</b>：TLS 的「每记录对称加密」在目标机（Solaris/SPARC）上
 * 要吃几个核 ⇒ 从而回答两个架构问题：</p>
 * <ol>
 *   <li><b>要不要为 TLS 引入 JNI</b>？—— 探针把 JDK 侧与 OpenSSL 侧放在一起测：
 *       OpenSSL 侧不需要写任何 JNI（{@code openssl speed -evp}），
 *       而「JNI 化的收益」的上限就是「OpenSSL 比 JDK 快多少」。</li>
 *   <li><b>要不要在前面挂 nginx 终止 TLS</b>？—— 收益来自「把 TLS 的核从 JVM 挪走」，
 *       所以先要知道这几个核现在占在谁身上。</li>
 * </ol>
 *
 * <p><b>跑法</b>（target 机上）：</p>
 * <pre>
 * # ① edap-native 必须先装进本地仓库（edap-crypto 的 test scope 依赖它）
 * mvn -pl edap-native -am install -DskipTests
 * # ② 只跑本用例（★ 不要加 -Dsurefire.failIfNoSpecifiedTests=false：
 * #    那样「测试类压根不存在」也会被吞掉，表现为 BUILD SUCCESS 但一行 Tests run 都没有）
 * mvn -pl edap-crypto test -DskipTests=false -Dtest=TlsCipherPerfTest
 * </pre>
 *
 * <p><b>结果</b>：控制台一张表（JDK 侧 ns/记录、MB/s、核@1Gbps、核@10Gbps）
 * + {@code openssl speed} 的原始输出 + JSON 报告
 * （{@code target/crypto-perf/tls-cipher-<os>-<arch>-<ts>.json}）。</p>
 *
 * <p><b>性能不做断言</b>（噪声会 flaky）—— 本用例只保证「测出来了、且 provider 归属被记录」；
 * 真正的判断由人拿两边的数字对照着看。</p>
 */
@Tag("perf")
public class TlsCipherPerfTest {

    @Test
    public void tlsCipherThroughput() throws Exception {
        System.out.println("================ TLS 对称加密吞吐测量 ================");
        System.out.println("os.name     : " + System.getProperty("os.name"));
        System.out.println("os.arch     : " + System.getProperty("os.arch"));
        System.out.println("java.version: " + System.getProperty("java.version"));
        System.out.println("======================================================");

        java.io.File report = TlsCipherProbe.run();

        // 只做「测出来了、且落盘了」的断言 —— 数值判断留给读报告的人
        assertTrue(report != null && report.isFile(),
                "没有产出 JSON 报告（report=" + report + "）");
    }
}
